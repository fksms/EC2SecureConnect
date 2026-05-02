package com.example.ec2secureconnect

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

class DragHandleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
