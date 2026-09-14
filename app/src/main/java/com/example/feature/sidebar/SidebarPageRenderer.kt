package com.example.feature.sidebar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.PageTypes
import com.example.feature.element.ElementActionRegistry
import com.example.feature.element.ElementCategory
import com.example.feature.element.ElementDescriptor
import com.example.feature.element.ElementPlacement
import com.example.feature.element.ElementPlacementManager
import com.example.feature.element.ElementRuntimeResolver

/**
 * SidebarPageRenderer: Page-type driven renderer for Sidebar page containers.
 *
 * Renders page views dynamically based on [PageTypes] and displays placed elements
 * strictly scoped to the active [containerId] + [pageId].
 *
 * Adheres strictly to the architectural constraints:
 * 1. Page-type driven rendering via existing [PageTypes] metadata without hard-coding.
 * 2. Minimum renderer/page-type contract without premature ad-hoc feature implementations.
 * 3. Lazy action dispatch through [ElementActionRegistry] without eager Element instantiation.
 * 4. Scoped Edit Mode affordances (element removal badges and '+ Add Item' tile).
 */
class SidebarPageRenderer(
    private val context: Context,
    private val onElementClick: ((ElementPlacement) -> Unit)? = null,
    private val onAddElementClick: (() -> Unit)? = null
) {
    private val density = context.resources.displayMetrics.density

    fun renderPage(state: SidebarRuntimeState): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(8), dp(4), dp(8), dp(24))
        }

        val pageType = state.currentPageType ?: PageTypes.HYBRID

        // 1. Page Title & Type Badge
        val badge = createPageBadge(state)
        root.addView(badge)

        // 2. Page-type specific placeholder card (minimal contract for specialized containers)
        val placeholder = createPageTypeHeader(pageType)
        if (placeholder != null) {
            root.addView(placeholder)
        }

        // 3. Placed Elements Grid (strictly scoped to containerId + pageId)
        val elementsSection = createElementsGrid(state)
        root.addView(elementsSection)

        return root
    }

    private fun createPageBadge(state: SidebarRuntimeState): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val pageType = state.currentPageType ?: PageTypes.HYBRID
        val (icon, accentColor) = resolvePageTypeTheme(pageType)

        val badgeView = TextView(context).apply {
            text = "$icon  ${state.currentPageTitle ?: "Page"}"
            setTextColor(accentColor)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.argb(40, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        container.addView(badgeView)

        if (state.isEditMode) {
            val editBadge = TextView(context).apply {
                text = "EDITING"
                setTextColor(Color.parseColor("#F43F5E"))
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(Color.parseColor("#33F43F5E"))
                }
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(6)
                }
            }
            container.addView(editBadge)
        }

        return container
    }

    /**
     * Minimal page-type contract hook for specialized page types.
     * Keeps container identity without premature ad-hoc feature implementations.
     */
    private fun createPageTypeHeader(pageType: String): View? {
        val (desc, accentColor) = when (pageType) {
            PageTypes.CALCULATOR -> "Calculator Container" to Color.parseColor("#FB923C")
            PageTypes.COMPASS -> "Compass Container" to Color.parseColor("#2DD4BF")
            PageTypes.MEDIA -> "Media Player Container" to Color.parseColor("#F472B6")
            PageTypes.APP_TRACKER -> "App Tracker Container" to Color.parseColor("#A78BFA")
            PageTypes.TOOLS -> "Tools Container" to Color.parseColor("#60A5FA")
            else -> return null
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#181825"))
                setStroke(dp(1), Color.argb(60, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val headerText = TextView(context).apply {
            text = desc
            setTextColor(accentColor)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
        }
        val subText = TextView(context).apply {
            text = "Scoped elements and actions for this page"
            setTextColor(Color.parseColor("#6C7086"))
            textSize = 9f
            setPadding(0, dp(2), 0, 0)
        }

        card.addView(headerText)
        card.addView(subText)
        return card
    }

    private fun createElementsGrid(state: SidebarRuntimeState): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val elements = state.currentElements
        val isEditMode = state.isEditMode

        if (elements.isEmpty() && !isEditMode) {
            val emptyCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(24), dp(12), dp(24))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#181825"))
                    setStroke(dp(1), Color.parseColor("#313244"))
                }
            }
            val emptyText = TextView(context).apply {
                text = "No elements on this page"
                setTextColor(Color.parseColor("#A6ADC8"))
                textSize = 12f
                gravity = Gravity.CENTER
            }
            val hintText = TextView(context).apply {
                text = "Tap ✏️ Edit to add items"
                setTextColor(Color.parseColor("#6C7086"))
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }
            emptyCard.addView(emptyText)
            emptyCard.addView(hintText)
            container.addView(emptyCard)
            return container
        }

        val grid = GridLayout(context).apply {
            columnCount = 3
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val registry = ElementActionRegistry.getInstance(context)

        val runtimeResolver = ElementRuntimeResolver.getInstance(context)

        for (element in elements) {
            val descriptor = registry.getDescriptor(element.actionKey)
            val customElement = runtimeResolver.resolveElement(element.actionKey)
            val renderContext = runtimeResolver.createRenderContext(
                placement = element,
                handleId = state.handleId,
                gesture = state.gesture,
                isEditMode = isEditMode,
                onActionTriggered = { onElementClick?.invoke(it) },
                onRemoveRequested = { toRemove ->
                    ElementPlacementManager.getInstance(context).removeElement(
                        state.containerId,
                        state.currentPageId ?: "",
                        toRemove.placementId
                    )
                }
            )

            val customView = customElement?.renderSidebarElement(renderContext)
            if (customView != null) {
                grid.addView(customView)
            } else {
                val tile = createElementTile(state, element, descriptor, isEditMode)
                grid.addView(tile)
            }
        }

        if (isEditMode) {
            val addTile = createAddElementTile {
                onAddElementClick?.invoke()
            }
            grid.addView(addTile)
        }

        container.addView(grid)
        return container
    }

    private fun createElementTile(
        state: SidebarRuntimeState,
        element: ElementPlacement,
        descriptor: ElementDescriptor?,
        isEditMode: Boolean
    ): View {
        val cell = FrameLayout(context).apply {
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(64)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            layoutParams = lp
        }

        val title = element.customTitle ?: descriptor?.displayName ?: element.actionKey
        val category = descriptor?.category ?: ElementCategory.TOOL_ACTION

        val bgDrawable = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#262738"))
            setStroke(dp(1), Color.parseColor("#3B3D54"))
        }

        val rippleColor = ColorStateList.valueOf(Color.parseColor("#4B4D66"))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = RippleDrawable(rippleColor, bgDrawable, null)
            isClickable = !isEditMode
            isFocusable = !isEditMode
            setOnClickListener {
                if (!isEditMode) {
                    onElementClick?.invoke(element)
                    ElementRuntimeResolver.getInstance(context).executePlacement(
                        placement = element,
                        handleId = state.handleId,
                        gesture = state.gesture
                    )
                }
            }
        }

        val iconText = TextView(context).apply {
            text = resolveCategoryIcon(category, element.actionKey)
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val label = TextView(context).apply {
            text = title
            setTextColor(Color.parseColor("#CDD6F4"))
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }

        content.addView(iconText)
        content.addView(label)
        cell.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        if (isEditMode) {
            val removeBtn = TextView(context).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 9f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#E11D48"))
                }
                setOnClickListener {
                    ElementPlacementManager.getInstance(context).removeElement(
                        state.containerId,
                        state.currentPageId ?: "",
                        element.placementId
                    )
                }
            }
            val removeLp = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(1), dp(1), 0)
            }
            cell.addView(removeBtn, removeLp)
        }

        return cell
    }

    private fun createAddElementTile(onClick: () -> Unit): View {
        val cell = FrameLayout(context).apply {
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(64)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            layoutParams = lp
        }

        val bg = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#1E2030"))
            setStroke(dp(1), Color.parseColor("#6366F1"), dp(3).toFloat(), dp(2).toFloat())
        }

        val rippleColor = ColorStateList.valueOf(Color.parseColor("#4338CA"))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = RippleDrawable(rippleColor, bg, null)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val plusIcon = TextView(context).apply {
            text = "➕"
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val addText = TextView(context).apply {
            text = "Add Item"
            setTextColor(Color.parseColor("#818CF8"))
            textSize = 9f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        content.addView(plusIcon)
        content.addView(addText)
        cell.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return cell
    }

    private fun resolvePageTypeTheme(pageType: String): Pair<String, Int> = when (pageType) {
        PageTypes.HYBRID -> "⚡" to Color.parseColor("#818CF8")
        PageTypes.APPS -> "📱" to Color.parseColor("#34D399")
        PageTypes.WIDGETS -> "🧩" to Color.parseColor("#FBBF24")
        PageTypes.MEDIA -> "🎵" to Color.parseColor("#F472B6")
        PageTypes.TOOLS -> "🛠️" to Color.parseColor("#60A5FA")
        PageTypes.APP_TRACKER -> "📊" to Color.parseColor("#A78BFA")
        PageTypes.CALCULATOR -> "🧮" to Color.parseColor("#FB923C")
        PageTypes.COMPASS -> "🧭" to Color.parseColor("#2DD4BF")
        else -> "📄" to Color.parseColor("#94A3B8")
    }

    private fun resolveCategoryIcon(category: ElementCategory, actionKey: String): String = when {
        actionKey.contains("calc") -> "🧮"
        actionKey.contains("compass") -> "🧭"
        actionKey.contains("media") || actionKey.contains("music") -> "🎵"
        actionKey.contains("torch") || actionKey.contains("flash") -> "🔦"
        actionKey.contains("wifi") -> "📶"
        actionKey.contains("bluetooth") -> "ᛒ"
        actionKey.contains("screenshot") -> "📸"
        actionKey.contains("setting") -> "⚙️"
        actionKey.contains("camera") -> "📷"
        actionKey.contains("browser") || actionKey.contains("web") -> "🌐"
        actionKey.contains("note") -> "📝"
        actionKey.contains("radar") -> "📡"
        category == ElementCategory.ANOTHER_APP_LINK -> "📱"
        category == ElementCategory.ANDROID_WIDGET -> "🧩"
        category == ElementCategory.SCREEN_OVERLAY -> "🪟"
        category == ElementCategory.TOOL_ACTION -> "🛠️"
        category == ElementCategory.SIDEBAR_PAGE_CONTENT -> "📄"
        category == ElementCategory.FULL_SCREEN_CONTENT -> "🖥️"
        else -> "⚡"
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
