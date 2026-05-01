package com.example.ec2secureconnect

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ec2secureconnect.databinding.ActivityMainBinding
import com.example.ec2secureconnect.databinding.DialogProfileBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ProfilesAdapter
    private val profiles = mutableListOf<SsmProfile>()
    private var pendingConnectProfile: SsmProfile? = null
    private var lastShownError: String? = null

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TunnelConnectionStore.ACTION_STATE_CHANGED) {
                renderProfiles()
            }
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val profile = pendingConnectProfile
            pendingConnectProfile = null
            if (granted && profile != null) {
                SsmTunnelForegroundService.start(this, profile)
            } else if (!granted) {
                Toast.makeText(
                    this, R.string.notification_permission_required, Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)

            val bottomInset = systemBars.bottom
            binding.profilesRecyclerView.setPadding(
                binding.profilesRecyclerView.paddingLeft,
                binding.profilesRecyclerView.paddingTop,
                binding.profilesRecyclerView.paddingRight,
                bottomInset + dpToPx(96)
            )

            val fabParams = binding.fabAddProfile.layoutParams as ViewGroup.MarginLayoutParams
            fabParams.bottomMargin = bottomInset + dpToPx(16)
            binding.fabAddProfile.layoutParams = fabParams

            insets
        }

        val appVersionStr = try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            getString(R.string.app_version_footer, packageInfo.versionName)
        } catch (_: Exception) {
            "v1.0.0"
        }

        adapter = ProfilesAdapter(
            appVersion = appVersionStr,
            onEdit = ::showProfileDialog,
            onDelete = ::confirmDelete,
            onConnect = ::toggleConnection
        )
        binding.profilesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.profilesRecyclerView.adapter = adapter
        binding.fabAddProfile.setOnClickListener { showProfileDialog(null) }

        loadProfiles()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            connectionReceiver,
            IntentFilter(TunnelConnectionStore.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        renderProfiles()
    }

    override fun onStop() {
        unregisterReceiver(connectionReceiver)
        super.onStop()
    }

    private fun loadProfiles() {
        lifecycleScope.launch {
            profiles.clear()
            profiles += ProfileStorage.loadProfiles(this@MainActivity)
            renderProfiles()
        }
    }

    private fun saveProfiles() {
        lifecycleScope.launch {
            ProfileStorage.saveProfiles(this@MainActivity, profiles)
        }
    }

    private fun renderProfiles() {
        val state = TunnelConnectionStore.load(this)
        adapter.submitData(profiles.toList(), state)
        binding.emptyState.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        if (state.status == TunnelStatus.ERROR && !state.message.isNullOrBlank() && state.message != lastShownError) {
            lastShownError = state.message
            Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
        } else if (state.status != TunnelStatus.ERROR) {
            lastShownError = null
        }
    }

    private fun showProfileDialog(existing: SsmProfile?) {
        val dialogBinding = DialogProfileBinding.inflate(layoutInflater)
        val regions = resources.getStringArray(R.array.aws_regions)
        val regionAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, regions)
        dialogBinding.regionDropdown.setAdapter(regionAdapter)

        if (existing != null) {
            dialogBinding.profileNameEditText.setText(existing.name)
            dialogBinding.accessKeyEditText.setText(existing.accessKey)
            dialogBinding.secretKeyEditText.setText(existing.secretAccessKey)
            dialogBinding.regionDropdown.setText(existing.region, false)
            dialogBinding.instanceIdEditText.setText(existing.instanceId)
            dialogBinding.remotePortEditText.setText(existing.remotePort.toString())
            dialogBinding.localPortEditText.setText(existing.localPort.toString())
        }

        val dialog = MaterialAlertDialogBuilder(this).setTitle(
            if (existing == null) {
                R.string.profile_dialog_add_title
            } else {
                R.string.profile_dialog_edit_title
            }
        ).setView(dialogBinding.root).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null).create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val profile = buildProfileFromInputs(dialogBinding, existing?.id)
                    if (profile != null) {
                        upsertProfile(profile)
                        dialog.dismiss()
                    }
                }
        }
        dialog.show()
    }

    private fun buildProfileFromInputs(
        dialogBinding: DialogProfileBinding, existingId: String?
    ): SsmProfile? {
        clearErrors(dialogBinding)

        val name = dialogBinding.profileNameEditText.text?.toString()?.trim().orEmpty()
        val accessKey = dialogBinding.accessKeyEditText.text?.toString()?.trim().orEmpty()
        val secretKey = dialogBinding.secretKeyEditText.text?.toString()?.trim().orEmpty()
        val region = dialogBinding.regionDropdown.text?.toString()?.trim().orEmpty()
        val instanceId = dialogBinding.instanceIdEditText.text?.toString()?.trim().orEmpty()
        val remotePort = dialogBinding.remotePortEditText.text?.toString()?.trim().orEmpty()
        val localPort = dialogBinding.localPortEditText.text?.toString()?.trim().orEmpty()

        var hasError = false
        if (name.isEmpty()) {
            dialogBinding.profileNameInput.error = getString(R.string.validation_required)
            hasError = true
        }
        if (accessKey.isEmpty()) {
            dialogBinding.accessKeyInput.error = getString(R.string.validation_required)
            hasError = true
        }
        if (secretKey.isEmpty()) {
            dialogBinding.secretKeyInput.error = getString(R.string.validation_required)
            hasError = true
        }
        if (region.isEmpty()) {
            dialogBinding.regionInput.error = getString(R.string.validation_required)
            hasError = true
        }
        if (instanceId.isEmpty()) {
            dialogBinding.instanceIdInput.error = getString(R.string.validation_required)
            hasError = true
        }

        val parsedRemotePort = remotePort.toIntOrNull()
        if (parsedRemotePort == null || parsedRemotePort !in 1..65535) {
            dialogBinding.remotePortInput.error = getString(R.string.validation_port)
            hasError = true
        }

        val parsedLocalPort = localPort.toIntOrNull()
        if (parsedLocalPort == null || parsedLocalPort !in 1..65535) {
            dialogBinding.localPortInput.error = getString(R.string.validation_port)
            hasError = true
        } else if (profiles.any { it.localPort == parsedLocalPort && it.id != existingId }) {
            dialogBinding.localPortInput.error = getString(R.string.validation_port_duplicate)
            hasError = true
        }

        if (hasError) {
            return null
        }

        return SsmProfile(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            accessKey = accessKey,
            secretAccessKey = secretKey,
            region = region,
            instanceId = instanceId,
            remotePort = parsedRemotePort ?: 0,
            localPort = parsedLocalPort ?: 0
        )
    }

    private fun clearErrors(dialogBinding: DialogProfileBinding) {
        dialogBinding.profileNameInput.error = null
        dialogBinding.accessKeyInput.error = null
        dialogBinding.secretKeyInput.error = null
        dialogBinding.regionInput.error = null
        dialogBinding.instanceIdInput.error = null
        dialogBinding.remotePortInput.error = null
        dialogBinding.localPortInput.error = null
    }

    private fun upsertProfile(profile: SsmProfile) {
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile
        } else {
            profiles.add(profile)
        }
        saveProfiles()
        renderProfiles()
    }

    private fun confirmDelete(profile: SsmProfile) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.delete_profile_title)
            .setMessage(R.string.delete_profile_message).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val state = TunnelConnectionStore.load(this)
                if (state.activeProfileId == profile.id && (state.status == TunnelStatus.CONNECTING || state.status == TunnelStatus.CONNECTED)) {
                    SsmTunnelForegroundService.stop(this)
                }
                profiles.removeAll { it.id == profile.id }
                saveProfiles()
                renderProfiles()
            }.show()
    }

    private fun toggleConnection(profile: SsmProfile) {
        val state = TunnelConnectionStore.load(this)
        if (state.activeProfileId == profile.id && (state.status == TunnelStatus.CONNECTING || state.status == TunnelStatus.CONNECTED)) {
            SsmTunnelForegroundService.stop(this)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingConnectProfile = profile
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        SsmTunnelForegroundService.start(this, profile)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
