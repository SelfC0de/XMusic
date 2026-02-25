package com.selfcode.xmusic.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.selfcode.xmusic.R
import com.selfcode.xmusic.data.Track

class TrackAdapter(
    private val onDownload: (Track) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private var tracks: List<Track> = emptyList()
    private var selectedPos = -1

    fun submit(list: List<Track>) {
        tracks = list
        selectedPos = -1
        notifyDataSetChanged()
    }

    fun getSelected(): Track? = tracks.getOrNull(selectedPos)

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.imgCover)
        val title: TextView = v.findViewById(R.id.tvTitle)
        val artist: TextView = v.findViewById(R.id.tvArtist)
        val duration: TextView = v.findViewById(R.id.tvDuration)
        val root: View = v
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false))

    override fun getItemCount() = tracks.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = tracks[position]
        holder.title.text = track.title
        holder.artist.text = track.artist
        holder.duration.text = track.duration
        holder.root.isSelected = position == selectedPos

        Glide.with(holder.cover)
            .load(track.coverUrl)
            .placeholder(R.drawable.ic_music_placeholder)
            .into(holder.cover)

        holder.root.setOnClickListener {
            val prev = selectedPos
            selectedPos = holder.adapterPosition
            notifyItemChanged(prev)
            notifyItemChanged(selectedPos)
        }

        holder.root.setOnLongClickListener {
            onDownload(track)
            true
        }
    }
}
