package com.example.llama

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.TurnStats

data class Message(
    val id: String,
    val content: String,
    val isUser: Boolean,
    /** Token accounting for this answer. Null on user turns, on an answer still being
     *  generated, and on answers written before the app recorded stats. */
    val stats: TurnStats? = null,
    /** True when the user stopped this answer part-way. See [StoredMessage.truncated]. */
    val truncated: Boolean = false,
)

class MessageAdapter(
    private val messages: List<Message>,
    private val onCopy: (String) -> Unit,
    private val onRegenerate: () -> Unit,
    private val onStats: (Message) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        const val PAYLOAD_TEXT = "text"
        const val PAYLOAD_DONE = "done"
        // Messages never span the whole row; the gap keeps the speaker readable.
        private const val MAX_WIDTH_FRACTION = 0.84f
    }

    private val rendered = HashMap<String, CharSequence>()
    private val renderedFrom = HashMap<String, String>()
    private val animated = HashSet<String>()

    // Chat text size from Settings; MainActivity refreshes it on resume.
    var textSizeSp = Settings.TEXT_SIZES_SP[Settings.DEF_TEXT_SIZE]

    override fun getItemViewType(position: Int) =
        if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = if (viewType == VIEW_TYPE_USER) R.layout.item_message_user
        else R.layout.item_message_assistant
        val holder = MessageViewHolder(inflater.inflate(layout, parent, false))
        // Percent max width keeps bubbles readable from small phones to tablets.
        if (parent.width > 0) holder.text.maxWidth = (parent.width * MAX_WIDTH_FRACTION).toInt()
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) showActions(holder.text, messages[pos], pos)
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        val vh = holder as MessageViewHolder
        vh.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        if (animated.add(msg.id) && position >= itemCount - 2) Anim.enter(vh.itemView)
        else Anim.clear(vh.itemView)
        when {
            msg.isUser -> showText(vh, msg.content)
            msg.content.isEmpty() -> showTyping(vh)
            else -> showText(vh, spanned(msg, vh.itemView.context))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val vh = holder as MessageViewHolder
        val msg = messages[position]
        when {
            payloads.contains(PAYLOAD_DONE) -> showText(vh, spanned(msg, vh.itemView.context))
            payloads.contains(PAYLOAD_TEXT) ->
                if (msg.content.isEmpty()) showTyping(vh) else showText(vh, msg.content)
            else -> onBindViewHolder(holder, position)
        }
    }

    override fun getItemCount() = messages.size

    private fun spanned(msg: Message, context: Context): CharSequence {
        if (renderedFrom[msg.id] == msg.content) rendered[msg.id]?.let { return it }
        val cs = Markdown.render(msg.content, context)
        rendered[msg.id] = cs
        renderedFrom[msg.id] = msg.content
        return cs
    }

    private fun showTyping(vh: MessageViewHolder) {
        vh.text.visibility = View.GONE
        vh.typing?.visibility = View.VISIBLE
    }

    private fun showText(vh: MessageViewHolder, cs: CharSequence) {
        vh.typing?.visibility = View.GONE
        vh.text.visibility = View.VISIBLE
        vh.text.text = cs
    }

    private fun showActions(anchor: View, msg: Message, position: Int) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, 1, 0, R.string.action_copy)
        if (!msg.isUser && position == messages.size - 1) {
            popup.menu.add(0, 2, 1, R.string.action_regenerate)
        }
        // Offered on every answer, including ones with no stats recorded - a generated reply
        // that silently lacks the entry would read as a bug. The dialog explains the absence.
        if (!msg.isUser && msg.content.isNotEmpty()) {
            popup.menu.add(0, 3, 2, R.string.action_stats)
        }
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> onCopy(msg.content)
                2 -> onRegenerate()
                3 -> onStats(msg)
            }
            true
        }
        popup.show()
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.msg_content)
        val typing: View? = view.findViewById(R.id.typing)
    }
}
