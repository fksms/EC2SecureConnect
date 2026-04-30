package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/ssm"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session"
	_ "github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session/portsession"
)

const startSessionOperation = "StartSession"
const statusEventPrefix = "SSM_CLIENT_EVENT:"
const defaultConnectTimeout = 20 * time.Second

type options struct {
	accessKeyID     string
	secretAccessKey string
	sessionToken    string
	region          string
	instanceID      string
	remotePort      int
	localPort       int
	connectTimeout  time.Duration
	statusEvents    bool
}

type statusEvent struct {
	Event   string `json:"event"`
	Message string `json:"message,omitempty"`
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "ssm-client: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	opts, err := parseFlags()
	if err != nil {
		return err
	}

	ctx := context.Background()
	restoreEnv, err := setPluginCredentialEnv(opts)
	if err != nil {
		return err
	}
	defer restoreEnv()

	cfg, err := awsconfig.LoadDefaultConfig(
		ctx,
		awsconfig.WithRegion(opts.region),
		awsconfig.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(
			opts.accessKeyID,
			opts.secretAccessKey,
			opts.sessionToken,
		)),
	)
	if err != nil {
		return fmt.Errorf("load AWS config: %w", err)
	}

	client := ssm.NewFromConfig(cfg)
	startInput := &ssm.StartSessionInput{
		Target:       aws.String(opts.instanceID),
		DocumentName: aws.String("AWS-StartPortForwardingSession"),
		Parameters: map[string][]string{
			"portNumber":      {strconv.Itoa(opts.remotePort)},
			"localPortNumber": {strconv.Itoa(opts.localPort)},
		},
	}

	startOutput, err := client.StartSession(ctx, startInput)
	if err != nil {
		return fmt.Errorf("start SSM session: %w", err)
	}

	startOutputJSON, err := json.Marshal(startOutput)
	if err != nil {
		return fmt.Errorf("marshal StartSession output: %w", err)
	}

	startRequestJSON, err := json.Marshal(startInput)
	if err != nil {
		return fmt.Errorf("marshal StartSession input: %w", err)
	}

	pluginArgs := []string{
		os.Args[0],
		string(startOutputJSON),
		opts.region,
		startSessionOperation,
		"",
		string(startRequestJSON),
		resolveSSMEndpoint(opts.region),
	}

	var pluginOutput bytes.Buffer
	pluginDone := make(chan error, 1)
	go func() {
		session.ValidateInputAndStartSession(pluginArgs, &pluginOutput)
		pluginDone <- detectPluginError(pluginOutput.String())
	}()

	if err := waitForTunnelReady(opts.localPort, opts.connectTimeout, pluginDone); err != nil {
		emitStatusEvent(opts, "error", err.Error())
		return err
	}
	emitStatusEvent(
		opts,
		"connected",
		fmt.Sprintf("localhost:%d is forwarding to remote port %d", opts.localPort, opts.remotePort),
	)

	if err := <-pluginDone; err != nil {
		emitStatusEvent(opts, "error", err.Error())
		return err
	}

	emitStatusEvent(opts, "stopped", "Session ended")
	return nil
}

func parseFlags() (options, error) {
	var opts options
	flagSet := flag.NewFlagSet(os.Args[0], flag.ContinueOnError)
	flagSet.SetOutput(io.Discard)

	flagSet.StringVar(&opts.accessKeyID, "access-key", "", "AWS access key ID")
	flagSet.StringVar(&opts.secretAccessKey, "secret-key", "", "AWS secret access key")
	flagSet.StringVar(&opts.sessionToken, "session-token", "", "AWS session token (optional)")
	flagSet.StringVar(&opts.region, "region", "", "AWS region")
	flagSet.StringVar(&opts.instanceID, "instance-id", "", "Target EC2 instance ID")
	flagSet.IntVar(&opts.remotePort, "remote-port", 0, "Remote port to forward")
	flagSet.IntVar(&opts.localPort, "local-port", 0, "Local port to bind")
	flagSet.DurationVar(&opts.connectTimeout, "connect-timeout", 0, "Time to wait for the local tunnel to become ready")
	flagSet.BoolVar(&opts.statusEvents, "status-events", false, "Emit machine-readable status events")
	if err := flagSet.Parse(os.Args[1:]); err != nil {
		return opts, err
	}

	opts.accessKeyID = firstNonEmpty(opts.accessKeyID, os.Getenv("AWS_ACCESS_KEY"))
	opts.secretAccessKey = firstNonEmpty(opts.secretAccessKey, os.Getenv("AWS_SECRET_KEY"))
	opts.sessionToken = firstNonEmpty(opts.sessionToken, os.Getenv("AWS_SESSION_TOKEN"))
	opts.region = firstNonEmpty(opts.region, os.Getenv("AWS_REGION"))
	opts.instanceID = firstNonEmpty(opts.instanceID, os.Getenv("EC2_INSTANCE_ID"))

	if opts.remotePort == 0 {
		value, err := intFromEnv("REMOTE_PORT")
		if err != nil {
			return opts, err
		}
		opts.remotePort = value
	}
	if opts.localPort == 0 {
		value, err := intFromEnv("LOCAL_PORT")
		if err != nil {
			return opts, err
		}
		opts.localPort = value
	}
	if opts.connectTimeout == 0 {
		value, err := durationFromEnv("CONNECT_TIMEOUT")
		if err != nil {
			return opts, err
		}
		opts.connectTimeout = value
	}
	if opts.connectTimeout == 0 {
		opts.connectTimeout = defaultConnectTimeout
	}

	if opts.accessKeyID == "" {
		return opts, errors.New("missing --access-key")
	}
	if opts.secretAccessKey == "" {
		return opts, errors.New("missing --secret-key")
	}
	if opts.region == "" {
		return opts, errors.New("missing --region")
	}
	if opts.instanceID == "" {
		return opts, errors.New("missing --instance-id")
	}
	if opts.remotePort <= 0 || opts.remotePort > 65535 {
		return opts, errors.New("--remote-port must be between 1 and 65535")
	}
	if opts.localPort <= 0 || opts.localPort > 65535 {
		return opts, errors.New("--local-port must be between 1 and 65535")
	}

	return opts, nil
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}

func intFromEnv(key string) (int, error) {
	value := os.Getenv(key)
	if value == "" {
		return 0, nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return 0, fmt.Errorf("invalid %s: %w", key, err)
	}
	return parsed, nil
}

func durationFromEnv(key string) (time.Duration, error) {
	value := os.Getenv(key)
	if value == "" {
		return 0, nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("invalid %s: %w", key, err)
	}
	return parsed, nil
}

func setPluginCredentialEnv(opts options) (func(), error) {
	env := map[string]string{
		"AWS_ACCESS_KEY_ID":     opts.accessKeyID,
		"AWS_SECRET_ACCESS_KEY": opts.secretAccessKey,
		"AWS_REGION":            opts.region,
		"AWS_DEFAULT_REGION":    opts.region,
	}
	if opts.sessionToken != "" {
		env["AWS_SESSION_TOKEN"] = opts.sessionToken
	}

	previous := map[string]*string{}
	for key, value := range env {
		v, ok := os.LookupEnv(key)
		if ok {
			vCopy := v
			previous[key] = &vCopy
		} else {
			previous[key] = nil
		}
		if err := os.Setenv(key, value); err != nil {
			return nil, fmt.Errorf("set %s: %w", key, err)
		}
	}

	restore := func() {
		for key, value := range previous {
			if value == nil {
				_ = os.Unsetenv(key)
				continue
			}
			_ = os.Setenv(key, *value)
		}
	}

	return restore, nil
}

func resolveSSMEndpoint(region string) string {
	if strings.HasPrefix(region, "cn-") {
		return fmt.Sprintf("https://ssm.%s.amazonaws.com.cn", region)
	}
	return fmt.Sprintf("https://ssm.%s.amazonaws.com", region)
}

func waitForTunnelReady(localPort int, timeout time.Duration, pluginDone <-chan error) error {
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()

	timer := time.NewTimer(timeout)
	defer timer.Stop()

	for {
		select {
		case err := <-pluginDone:
			if err != nil {
				return err
			}
			return errors.New("session ended before the tunnel became ready")
		case <-ticker.C:
			if isPortOpen(localPort) {
				return nil
			}
		case <-timer.C:
			return fmt.Errorf("timed out waiting for localhost:%d to become ready", localPort)
		}
	}
}

func isPortOpen(port int) bool {
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 200*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

func emitStatusEvent(opts options, event, message string) {
	if !opts.statusEvents {
		return
	}
	payload, err := json.Marshal(statusEvent{
		Event:   event,
		Message: message,
	})
	if err != nil {
		return
	}
	fmt.Println(statusEventPrefix + string(payload))
}

func detectPluginError(output string) error {
	trimmed := strings.TrimSpace(output)
	if trimmed == "" {
		return nil
	}

	errorPrefixes := []string{
		"Cannot perform start session:",
		"Invalid Operation",
		"Unknown operation",
	}
	for _, prefix := range errorPrefixes {
		if strings.Contains(trimmed, prefix) {
			return errors.New(trimmed)
		}
	}

	return nil
}
