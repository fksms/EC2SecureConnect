package com.example.ec2secureconnect

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.ec2secureconnect.databinding.ItemProfileBinding

class ProfilesAdapter(
    private val onEdit: (SsmProfile) -> Unit,
    private val onDelete: (SsmProfile) -> Unit,
    private val onConnect: (SsmProfile) -> Unit
) : RecyclerView.Adapter<ProfilesAdapter.ProfileViewHolder>() {

    private var profiles: List<SsmProfile> = emptyList()
    private var connectionState = TunnelConnectionState(
        activeProfileId = null,
        lastProfileId = null,
        status = TunnelStatus.DISCONNECTED,
        message = null
    )

    fun submitData(newProfiles: List<SsmProfile>, newState: TunnelConnectionState) {
        profiles = newProfiles
        connectionState = newState
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(profiles[position], connectionState)
    }

    override fun getItemCount(): Int = profiles.size

    inner class ProfileViewHolder(
        private val binding: ItemProfileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: SsmProfile, state: TunnelConnectionState) {
            val isActive = state.activeProfileId == profile.id
            val status = when {
                isActive -> state.status
                state.lastProfileId == profile.id && state.status == TunnelStatus.ERROR -> TunnelStatus.ERROR
                else -> TunnelStatus.DISCONNECTED
            }
            val context = binding.root.context

            binding.profileNameText.text = profile.name
            binding.statusText.text = context.getString(
                when (status) {
                    TunnelStatus.CONNECTED -> R.string.connected
                    TunnelStatus.CONNECTING -> R.string.connecting
                    TunnelStatus.ERROR -> R.string.connection_failed
                    TunnelStatus.DISCONNECTED -> R.string.disconnected
                }
            )
            binding.detailsText.text = context.getString(
                R.string.connection_details,
                profile.region,
                profile.instanceId,
                profile.localPort,
                profile.remotePort
            )
            binding.accessKeyText.text = profile.accessKey.masked()
            binding.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(
                context, when (status) {
                    TunnelStatus.CONNECTED -> R.color.status_connected
                    TunnelStatus.CONNECTING -> R.color.status_connecting
                    TunnelStatus.ERROR -> R.color.status_error
                    TunnelStatus.DISCONNECTED -> R.color.status_disconnected
                }
            )
            binding.messageText.text = state.message.orEmpty()
            binding.messageText.visibility =
                if (status == TunnelStatus.ERROR && !state.message.isNullOrBlank()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            binding.connectButton.text = context.getString(
                if (isActive && (status == TunnelStatus.CONNECTING || status == TunnelStatus.CONNECTED)) {
                    R.string.disconnect
                } else {
                    R.string.connect
                }
            )
            binding.connectButton.setOnClickListener { onConnect(profile) }
            binding.editButton.setOnClickListener { onEdit(profile) }
            binding.deleteButton.setOnClickListener { onDelete(profile) }
        }
    }
}

private fun String.masked(): String {
    if (length <= 4) {
        return this
    }
    return take(4) + "*".repeat(length - 4)
}
