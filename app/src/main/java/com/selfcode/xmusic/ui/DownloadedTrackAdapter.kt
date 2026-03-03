package com.selfcode.xmusic.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import com.selfcode.xmusic.R
import com.selfcode.xmusic.databinding.ItemDownloadedTrackBinding

data class DownloadedTrack(
    val title: String,
    val artist: String,
    val filePath: String,
    val fileName: String
)

class DownloadedTrackAdapter(
    private val onPlay: (DownloadedTrack) -> Unit,
    private val onDelete: (DownloadedTrack) -> Unit
) : RecyclerView.Adapter<DownloadedTrackAdapter.VH>() {

    private val items = mutableListOf<DownloadedTrack>()
    private var playingPos = -1

    inner class VH(val b: ItemDownloadedTrackBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDownloadedTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = items[position]
        val b = holder.b

        b.tvTitle.text = track.title
        b.tvArtist.text = track.artist

        b.btnPlay.setImageResource(
            if (position == playingPos) R.drawable.ic_pause else R.drawable.ic_play
        )

        b.btnPlay.setOnClickListener { onPlay(track) }
        b.btnDelete.setOnClickListener { onDelete(track) }

        b.cardRoot.setOnClickListener { onPlay(track) }

        val anim = AnimationUtils.loadAnimation(b.root.context, R.anim.slide_up_fade)
        anim.startOffset = (position % 10 * 40).toLong()
        b.root.startAnimation(anim)
    }

    fun submit(newItems: List<DownloadedTrack>) {
        items.clear()
        items.addAll(newItems)
        playingPos = -1
        notifyDataSetChanged()
    }

    fun setPlayingPath(path: String?) {
        val prev = playingPos
        playingPos = if (path != null) items.indexOfFirst { it.filePath == path } else -1
        if (prev >= 0) notifyItemChanged(prev)
        if (playingPos >= 0) notifyItemChanged(playingPos)
    }
}
