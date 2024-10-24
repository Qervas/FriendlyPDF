   package tech.ohao.friendlypdf

   import android.content.Intent
   import android.content.res.Configuration
   import android.os.Bundle
   import android.os.Handler
   import android.os.Looper
   import androidx.appcompat.app.AppCompatActivity
   import androidx.constraintlayout.widget.ConstraintLayout
   import androidx.core.content.ContextCompat
   import androidx.preference.PreferenceManager

   class SplashActivity : AppCompatActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
           setContentView(R.layout.activity_splash) // Set the splash layout

           // Get the root layout
           val splashRoot = findViewById<ConstraintLayout>(R.id.splash_root)

           // Check system dark mode setting
           val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
           val isDarkMode = currentNightMode == Configuration.UI_MODE_NIGHT_YES

           // Set background color based on system theme
           val backgroundColor = if (isDarkMode) {
               ContextCompat.getColor(this, R.color.black) // Use a dark color
           } else {
               ContextCompat.getColor(this, R.color.splash_background_color) // Use a bright color
           }
           splashRoot.setBackgroundColor(backgroundColor)

           // Add a delay to show the splash screen for a short time
           Handler(Looper.getMainLooper()).postDelayed({
               // Start MainActivity and finish this activity
               startActivity(Intent(this, MainActivity::class.java))
               finish()
           }, 1000) // 1000ms = 1 second delay
       }
   }
