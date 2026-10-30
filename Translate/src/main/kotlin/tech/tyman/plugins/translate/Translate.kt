package tech.tyman.plugins.translate

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.api.commands.ApplicationCommandType
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.node.EditedMessageNode
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import org.json.JSONArray
import java.lang.reflect.Field
import java.util.regex.Pattern

@AliucordPlugin
class Translate : Plugin() {
    lateinit var pluginIcon: Drawable
    private val translatedMessages = mutableMapOf<Long, TranslateSuccessData>()
    private var chatList: WidgetChatList? = null
    // Messages currently being fetched, to avoid duplicate requests when tapping repeatedly
    private val translating = mutableSetOf<Long>()
    private val messageLoggerEditedRegex = Pattern.compile("(?:.+ \\(.+: .+\\)\\n)+(.+)\$")

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    override fun load(ctx: Context) {
        pluginIcon = ContextCompat.getDrawable(ctx, R.e.ic_locale_24dp)!!
    }

    override fun start(context: Context) {
        patchMessageTranslateButton()
        patchProcessMessageText()
        commands.registerCommand(
                "translate",
                "Translates text from one language to another, sends by default",
                listOf(
                        Utils.createCommandOption(ApplicationCommandType.STRING, "text", "The text to translate"),
                        Utils.createCommandOption(ApplicationCommandType.STRING, "to", "The language to translate to (default en, must be a language code described in plugin settings)", choices = languageCodeChoices),
                        Utils.createCommandOption(ApplicationCommandType.STRING, "from", "The language to translate from (default auto, must be a language code described in plugin settings)", choices = languageCodeChoices),
                        Utils.createCommandOption(ApplicationCommandType.BOOLEAN, "send", "Whether or not to send the message in chat (default true)")
                )
        ) { ctx ->
            val translateData = translateMessage(
                ctx.getRequiredString("text"),
                ctx.getString("from"),
                ctx.getString("to")
            )
            if (translateData !is TranslateSuccessData) {
                with(translateData as TranslateErrorData) {
                    return@registerCommand CommandsAPI.CommandResult(
                        "$errorText ($errorCode)",
                        null,
                        false
                    )
                }
            }
            return@registerCommand CommandsAPI.CommandResult(
                translateData.translatedText,
                null,
                ctx.getBoolOrDefault("send", true)
            )
        }
    }

    private fun DraweeSpanStringBuilder.setTranslated(translateData: TranslateSuccessData, context: Context) {
        val contentStartIndex = messageLoggerEditedRegex.matcher(this.toString()).let {
            if (it.find()) {
                it.start(1)
            } else 0
        }
        this.replace(contentStartIndex, contentStartIndex + translateData.sourceText.length, translateData.translatedText)
        val textEnd = this.length
        this.append(" (translated: ${translateData.sourceLanguage} -> ${translateData.translatedLanguage})")
        this.setSpan(RelativeSizeSpan(0.75f), textEnd, this.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (textEnd != this.length) {
            this.setSpan(EditedMessageNode.Companion.`access$getForegroundColorSpan`(EditedMessageNode.Companion, context),
                    textEnd, this.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun patchProcessMessageText() {
        patcher.patch(WidgetChatList::class.java.getDeclaredConstructor(), Hook {
            chatList = it.thisObject as WidgetChatList
        })

        val mDraweeStringBuilder: Field = SimpleDraweeSpanTextView::class.java.getDeclaredField("mDraweeStringBuilder")
        mDraweeStringBuilder.isAccessible = true
        patcher.patch(WidgetChatListAdapterItemMessage::class.java, "processMessageText", arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java), Hook {
            val messageEntry = it.args[1] as MessageEntry
            val message = messageEntry.message ?: return@Hook
            val id = message.id
            val translateData = translatedMessages[id] ?: return@Hook
            if (translateData.showingOriginal) return@Hook
            if (translateData.sourceText != message.content) {
                translatedMessages.remove(id)
                return@Hook
            }
            val textView = it.args[0] as SimpleDraweeSpanTextView
            val builder = mDraweeStringBuilder[textView] as DraweeSpanStringBuilder?
                    ?: return@Hook
            val context = textView.context
            builder.setTranslated(translateData, context)
            textView.setDraweeSpanStringBuilder(builder)
        })
    }

    /** Translates the message (or toggles original/translated if it was already translated). */
    private fun toggleTranslation(messageId: Long, content: String) {
        val translationEntry = translatedMessages[messageId]
        if (translationEntry == null) {
            // If not translated yet, fetch and cache the translation, then rerender the message
            if (!translating.add(messageId)) return
            Utils.threadPool.execute {
                try {
                    val response = translateMessage(content)
                    if (response !is TranslateSuccessData) {
                        with(response as TranslateErrorData) {
                            Utils.showToast("$errorText ($errorCode)", true)
                            return@execute
                        }
                    }
                    translatedMessages[messageId] = response
                    chatList?.rerenderMessage(messageId)
                } catch (e: Throwable) {
                    logger.error("Failed to translate message", e)
                    Utils.showToast("Failed to translate message", true)
                } finally {
                    translating.remove(messageId)
                }
            }
        } else {
            // If translated, no need to translate anything, so just flip the showingOriginal property and rerender
            translationEntry.showingOriginal = !translationEntry.showingOriginal
            chatList?.rerenderMessage(messageId)
        }
    }

    private fun patchMessageTranslateButton() {
        val buttonTag = "TranslatePluginButton"
        val messageTextId = Utils.getResId("chat_list_adapter_item_text", "id")

        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChatListEntry::class.java),
            Hook {
                val holder = it.thisObject as WidgetChatListAdapterItemMessage
                val layout = holder.itemView as? ConstraintLayout ?: return@Hook
                val context = layout.context
                val density = context.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()

                val entry = it.args[1] as? MessageEntry
                val message = entry?.message
                val content: String? = message?.content

                // Get the existing button (views are recycled) or create it the first time
                var button = layout.findViewWithTag<ImageView>(buttonTag)
                if (button == null) {
                    button = ImageView(context).apply {
                        tag = buttonTag
                        id = View.generateViewId()
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        contentDescription = "Translate message"
                        val outValue = TypedValue()
                        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
                            setBackgroundResource(outValue.resourceId)
                        }
                    }
                    val params = ConstraintLayout.LayoutParams(dp(32), dp(32)).apply {
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        // Align with the top of the message text; fall back to the top of the item
                        if (messageTextId != 0 && layout.findViewById<View>(messageTextId) != null) {
                            topToTop = messageTextId
                        } else {
                            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        }
                        marginEnd = dp(4)
                    }
                    layout.addView(button, params)
                }

                if (message == null || content.isNullOrEmpty()) {
                    button.visibility = View.GONE
                    button.setOnClickListener(null)
                    return@Hook
                }

                val translationEntry = translatedMessages[message.id]
                val isTranslated = translationEntry != null && !translationEntry.showingOriginal

                // Fresh drawable per view, tinted: accent colour when the translation is being shown
                val icon = DrawableCompat.wrap(pluginIcon.constantState!!.newDrawable().mutate())
                DrawableCompat.setTint(
                    icon,
                    ColorCompat.getThemedColor(
                        context,
                        if (isTranslated) R.b.colorInteractiveActive else R.b.colorInteractiveNormal
                    )
                )
                button.setImageDrawable(icon)
                button.alpha = if (isTranslated) 1f else 0.7f
                button.contentDescription = if (isTranslated) "Show original" else "Translate message"
                button.visibility = View.VISIBLE

                val messageId = message.id
                button.setOnClickListener { _ -> toggleTranslation(messageId, content) }
            }
        )
    }

    override fun stop(context: Context?) = patcher.unpatchAll()

    private fun translateMessage(text: String, from: String? = null, to: String? = null): TranslateData {
        val toLang = to ?: settings.getString("defaultLanguage", "en")
        val fromLang = from ?: "auto"
        val queryBuilder = Http.QueryBuilder("https://translate.googleapis.com/translate_a/single").run {
            append("client", "gtx")
            append("sl", fromLang)
            append("tl", toLang)
            append("dt", "t")
            append("q", text)
        }
        val translatedJsonReq = Http.Request(queryBuilder.toString(), "GET").apply {
            setHeader("Content-Type", "application/json")
            setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4592.0 Safari/537.36")
        }.execute()

        if (!translatedJsonReq.ok()) {
            return when (translatedJsonReq.statusCode) {
                429 -> TranslateErrorData(
                    errorCode = 429,
                    errorText = "Translate API ratelimit reached. Please try again later."
                )
                else -> TranslateErrorData(
                    errorCode = translatedJsonReq.statusCode,
                    errorText = "An unknown error occurred. Please report this to the developer of Translate."
                )
            }
        }
        val parsedJson = JSONArray(translatedJsonReq.text())

        val translatedSections = parsedJson.getJSONArray(0)

        val translatedText = buildString {
            for (i in 0 until translatedSections.length()) {
                append(translatedSections.getJSONArray(i).getString(0))
            }
        }

        return TranslateSuccessData(
                sourceLanguage = parsedJson.getString(2),
                translatedLanguage = toLang,
                sourceText = text,
                translatedText = translatedText
        )
    }

//    private fun Message.guildId(): Long? {
//        val channel = ChannelWrapper(StoreStream.getChannels().getChannel(this.channelId))
//        return if (channel.isGuild()) channel.guildId else null
//    }
}
