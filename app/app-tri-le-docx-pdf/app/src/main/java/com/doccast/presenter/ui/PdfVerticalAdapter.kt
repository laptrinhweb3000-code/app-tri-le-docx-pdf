package com.doccast.presenter.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.doccast.presenter.databinding.ItemPdfVerticalPageBinding
import com.doccast.presenter.parser.PdfDocumentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfVerticalAdapter(
    private val engine: PdfDocumentEngine,
    private val targetWidthPx: Int,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PdfVerticalAdapter.PageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemPdfVerticalPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.renderJob?.cancel()
        holder.binding.tvVerticalPageNum.text = "Trang ${position + 1} / ${engine.pageCount}"
        holder.binding.ivVerticalPage.setImageBitmap(null)
        holder.binding.ivVerticalPage.minimumHeight = (targetWidthPx * 1.35f).toInt().coerceIn(500, 1800)

        holder.renderJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { engine.renderPage(position, targetWidthPx) }.getOrNull()
            }
            if (bmp != null && holder.bindingAdapterPosition == position) {
                holder.binding.ivVerticalPage.minimumHeight = 0
                holder.binding.ivVerticalPage.setImageBitmap(bmp)
            }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.renderJob?.cancel()
        holder.renderJob = null
    }

    override fun getItemCount(): Int = engine.pageCount

    class PageViewHolder(
        val binding: ItemPdfVerticalPageBinding,
        var renderJob: Job? = null
    ) : RecyclerView.ViewHolder(binding.root)
}
