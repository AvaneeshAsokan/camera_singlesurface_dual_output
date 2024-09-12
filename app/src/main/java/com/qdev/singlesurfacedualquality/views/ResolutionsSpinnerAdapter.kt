package com.qdev.singlesurfacedualquality.views

import android.app.Activity
import android.media.CamcorderProfile
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import com.qdev.singlesurfacedualquality.R
import com.qdev.singlesurfacedualquality.databinding.ResolutionSpinneritemBinding

class ResolutionsSpinnerAdapter(context: Activity, list: List<CamcorderProfile?>?) :
    ArrayAdapter<CamcorderProfile?>(context, R.layout.resolution_spinneritem, list!!) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createItemView(position, convertView, parent)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createItemView(position, convertView, parent)
    }

    private fun createItemView(position: Int, recycledView: View?, parent: ViewGroup): View {
        val profileItem = getItem(position)

        val binding = if (recycledView != null) {
            ResolutionSpinneritemBinding.bind(recycledView)
        } else ResolutionSpinneritemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        binding.txtItemname.text = when {
            profileItem?.videoFrameWidth!! < 720 ||
                    profileItem.videoFrameHeight < 720 -> {
                "vga${profileItem.videoFrameWidth}x${profileItem.videoFrameHeight}"
            }
            profileItem.videoFrameWidth in 720..1080 ||
                    profileItem.videoFrameHeight in 720..1080 -> {
                "hd${profileItem.videoFrameWidth}x${profileItem.videoFrameHeight}"
            }
            profileItem.videoFrameWidth in 1081..2048 ||
                    profileItem.videoFrameHeight in 1081..2048 -> {
                "hd2k${profileItem.videoFrameWidth}x${profileItem.videoFrameHeight}"
            }
            profileItem.videoFrameWidth in 2160..4096 ||
                    profileItem.videoFrameHeight in 2160..4096 -> {
                "hd4k${profileItem.videoFrameWidth}x${profileItem.videoFrameHeight}"
            }
            else -> "${profileItem.videoFrameWidth}x${profileItem.videoFrameHeight}"
        }

        return binding.root
    }

    override fun getFilter(): Filter {
        return object: Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults? = null
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = Unit
        }
    }
}