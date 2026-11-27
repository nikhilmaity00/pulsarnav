package com.pulsarnav.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pulsarnav.app.databinding.ItemFuelBinding
import com.pulsarnav.app.maintenance.FuelEntry
import java.util.Locale

class FuelListAdapter(
    private var theme: CockpitTheme,
    private var currencySymbol: String,
    private val onEntryClick: (FuelEntry) -> Unit
) : ListAdapter<FuelEntry, FuelListAdapter.FuelViewHolder>(DiffCallback) {

    fun updateStyle(newTheme: CockpitTheme, newCurrencySymbol: String) {
        theme = newTheme
        currencySymbol = newCurrencySymbol
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FuelViewHolder {
        val binding = ItemFuelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FuelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FuelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FuelViewHolder(
        private val binding: ItemFuelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FuelEntry) {
            binding.root.apply {
                setCardBackgroundColor(theme.cardColor)
                strokeColor = theme.cardBorderColor
                isClickable = true
                isFocusable = true
                setOnClickListener { onEntryClick(entry) }
            }
            binding.tvFuelDate.text = entry.formattedDate
            binding.tvFuelDate.setTextColor(theme.textPrimaryColor)
            binding.tvFuelOdo.text = String.format(
                Locale.US,
                "Odo: %.1f km • %.1f L",
                entry.odometerKm,
                entry.liters
            )
            binding.tvFuelOdo.setTextColor(theme.textTertiaryColor)
            binding.tvFuelCost.text = String.format(Locale.US, "%s%.0f", currencySymbol, entry.totalCostInr)
            binding.tvFuelCost.setTextColor(theme.accentColor)

            val kmpl = entry.calculatedKmpl
            if (kmpl != null) {
                binding.tvFuelMileage.text = String.format(Locale.US, "%.1f km/l", kmpl)
                binding.tvFuelMileage.visibility = View.VISIBLE
            } else {
                binding.tvFuelMileage.visibility = View.GONE
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<FuelEntry>() {
        override fun areItemsTheSame(oldItem: FuelEntry, newItem: FuelEntry): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: FuelEntry, newItem: FuelEntry): Boolean =
            oldItem == newItem
    }
}
