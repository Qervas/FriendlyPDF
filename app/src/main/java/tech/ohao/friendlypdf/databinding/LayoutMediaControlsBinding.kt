import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView

class LayoutMediaControlsBinding private constructor(
    val root: View,
    val btnPrevious: ImageButton,
    val btnPlayPause: ImageButton,
    val btnStop: ImageButton,
    val btnNext: ImageButton,
    val btnClose: ImageButton,
    val btnSpeed: ImageButton,
    val speedControlContainer: View,
    val speedSeekbar: SeekBar,
    val speedText: TextView,
    val audioProgress: SeekBar,
    val timeRemaining: TextView
)
