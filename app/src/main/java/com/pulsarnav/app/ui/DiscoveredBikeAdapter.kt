package com.pulsarnav.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pulsarnav.app.ble.DiscoveredBike
import com.pulsarnav.app.databinding.ItemDeviceBinding

class DiscoveredBikeAdapter(
    private var theme: CockpitTheme,
    private val onConnectClick: (DiscoveredBike) -> Unit
) : ListAdapter<DiscoveredBike, DiscoveredBikeAdapter.BikeViewHolder>(DiffCallback) {

    fun updateTheme(newTheme: CockpitTheme) {
        theme = newTheme
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BikeViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BikeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BikeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class BikeViewHolder(
        private val binding: ItemDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(bike: DiscoveredBike) {
            binding.root.setCardBackgroundColor(theme.cardColor)
            binding.root.strokeColor = theme.cardBorderColor
            binding.tvDeviceName.text = bike.name
            binding.tvDeviceName.setTextColor(theme.textPrimaryColor)
            binding.tvDeviceAddress.text = "${bike.address}  •  ${bike.rssi} dBm"
            binding.tvDeviceAddress.setTextColor(theme.textTertiaryColor)
            binding.btnConnectDevice.backgroundTintList = ColorStateList.valueOf(theme.primaryColor)
            binding.btnConnectDevice.setTextColor(theme.onPrimaryColor)
            binding.btnConnectDevice.setOnClickListener { onConnectClick(bike) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DiscoveredBike>() {
        override fun areItemsTheSame(oldItem: DiscoveredBike, newItem: DiscoveredBike): Boolean =
            oldItem.address == newItem.address

        override fun areContentsTheSame(oldItem: DiscoveredBike, newItem: DiscoveredBike): Boolean =
            oldItem.name == newItem.name && oldItem.rssi == newItem.rssi
    }
}
