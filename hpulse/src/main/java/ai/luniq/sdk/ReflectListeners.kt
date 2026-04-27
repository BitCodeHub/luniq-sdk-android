package ai.luniq.sdk

import android.view.View

/** Helper to read an existing OnClickListener via reflection so we can wrap (not replace) it. */
internal object ReflectListeners {
    fun getOnClickListener(view: View): View.OnClickListener? {
        return try {
            val listenerInfoMethod = View::class.java.getDeclaredMethod("getListenerInfo")
            listenerInfoMethod.isAccessible = true
            val info = listenerInfoMethod.invoke(view) ?: return null
            val field = info.javaClass.getDeclaredField("mOnClickListener")
            field.isAccessible = true
            field.get(info) as? View.OnClickListener
        } catch (_: Throwable) { null }
    }
}
