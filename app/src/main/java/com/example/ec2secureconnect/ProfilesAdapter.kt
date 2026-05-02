package com.example.ec2secureconnect

import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.ec2secureconnect.databinding.ItemFooterVersionBinding
import com.example.ec2secureconnect.databinding.ItemProfileBinding
import java.util.Collections

class ProfilesAdapter(
    private val appVersion: String,
    private val onEdit: (SsmProfile) -> Unit,
    private val onDelete: (SsmProfile) -> Unit,
    private val onConnect: (SsmProfile) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var profiles: List<SsmProfile> = emptyList()
    private var connectionState = TunnelConnectionState(emptyMap())

    companion object {
        private const val TYPE_PROFILE = 0
        private const val TYPE_FOOTER = 1
    }

    fun submitData(newProfiles: List<SsmProfile>, newState: TunnelConnectionState) {
        val diffCallback = ProfilesDiffCallback(profiles, newProfiles, connectionState, newState)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        profiles = newProfiles
        connectionState = newState
        diffResult.dispatchUpdatesTo(this)
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition >= profiles.size || toPosition >= profiles.size) return
        val mutableList = profiles.toMutableList()
        Collections.swap(mutableList, fromPosition, toPosition)
        profiles = mutableList
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < profiles.size) TYPE_PROFILE else TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_PROFILE) {
            val binding = ItemProfileBinding.inflate(inflater, parent, false)
            ProfileViewHolder(binding)
        } else {
            val binding = ItemFooterVersionBinding.inflate(inflater, parent, false)
            FooterViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ProfileViewHolder) {
            holder.bind(profiles[position], connectionState)
        } else if (holder is FooterViewHolder) {
            holder.bind(appVersion)
        }
    }

    override fun getItemCount(): Int = profiles.size + 1

    private class ProfilesDiffCallback(
        private val oldList: List<SsmProfile>,
        private val newList: List<SsmProfile>,
        private val oldState: TunnelConnectionState,
        private val newState: TunnelConnectionState
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size + 1
        override fun getNewListSize(): Int = newList.size + 1

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldIsFooter = oldItemPosition == oldList.size
            val newIsFooter = newItemPosition == newList.size
            if (oldIsFooter && newIsFooter) return true
            if (oldIsFooter || newIsFooter) return false
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldIsFooter = oldItemPosition == oldList.size
            val newIsFooter = newItemPosition == newList.size
            if (oldIsFooter && newIsFooter) return true
            if (oldIsFooter || newIsFooter) return false

            return oldList[oldItemPosition] == newList[newItemPosition] && oldState == newState
        }
    }

    class FooterViewHolder(private val binding: ItemFooterVersionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(version: String) {
            binding.versionText.text = version
        }
    }

    inner class ProfileViewHolder(
        private val binding: ItemProfileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: SsmProfile, state: TunnelConnectionState) {
            val statusInfo = state.profileStates[profile.id]
            val status = statusInfo?.status ?: TunnelStatus.DISCONNECTED
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

            val localhostText = "localhost:${profile.localPort}"
            val fullDetails = context.getString(
                R.string.connection_details,
                profile.region,
                profile.instanceId,
                profile.localPort,
                profile.remotePort
            )
            val spannable = SpannableString(fullDetails)
            val start = fullDetails.indexOf(localhostText)
            if (start >= 0) {
                spannable.setSpan(object : ClickableSpan() {
                    override fun onClick(view: View) {
                        val intent = Intent(Intent.ACTION_VIEW, "http://$localhostText".toUri())
                        context.startActivity(intent)
                    }
                }, start, start + localhostText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            binding.detailsText.text = spannable
            binding.detailsText.movementMethod = LinkMovementMethod.getInstance()

            binding.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(
                context, when (status) {
                    TunnelStatus.CONNECTED -> R.color.status_connected
                    TunnelStatus.CONNECTING -> R.color.status_connecting
                    TunnelStatus.ERROR -> R.color.status_error
                    TunnelStatus.DISCONNECTED -> R.color.status_disconnected
                }
            )
            binding.messageText.text = statusInfo?.message.orEmpty()
            binding.messageText.visibility =
                if (status == TunnelStatus.ERROR && !statusInfo?.message.isNullOrBlank()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            binding.connectButton.text = context.getString(
                if (status == TunnelStatus.CONNECTING || status == TunnelStatus.CONNECTED) {
                    R.string.disconnect
                } else {
                    R.string.connect
                }
            )
            binding.connectButton.setOnClickListener { onConnect(profile) }
            binding.editButton.setOnClickListener { onEdit(profile) }
            binding.deleteButton.setOnClickListener { onDelete(profile) }

            binding.dragHandle.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    onStartDrag(this)
                }
                false
            }
        }
    }
}
