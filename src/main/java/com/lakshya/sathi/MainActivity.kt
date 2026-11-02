package com.lakshya.sathi

import android.app.*
import android.content.*
import android.os.*
import android.speech.tts.*
import android.widget.*
import org.json.*
import java.util.*

fun alarmPi(c: Context, id: Long, text: String, h: Int, m: Int): PendingIntent {
    val it = Intent(c, AlarmReceiver::class.java)
        .putExtra("id", id).putExtra("text", text).putExtra("h", h).putExtra("m", m)
    return PendingIntent.getBroadcast(c, id.toInt(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

fun schedule(c: Context, id: Long, text: String, h: Int, m: Int) {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    if (cal.timeInMillis <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1)
    val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, alarmPi(c, id, text, h, m))
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", 0)
        val text = i.getStringExtra("text") ?: return
        val h = i.getIntExtra("h", 0)
        val m = i.getIntExtra("m", 0)
        schedule(c, id, text, h, m)
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("task", "काम की याद", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(c, "task")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("अब करने का समय")
            .setContentText(text)
            .setAutoCancel(true).build()
        nm.notify(id.toInt(), n)
        val pending = goAsync()
        var tts: TextToSpeech? = null
        tts = TextToSpeech(c.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(u: String?) {}
                    override fun onDone(u: String?) { tts?.shutdown(); pending.finish() }
                    override fun onError(u: String?) { tts?.shutdown(); pending.finish() }
                })
                tts?.speak("अभी का काम: $text", TextToSpeech.QUEUE_FLUSH, null, "u")
            } else pending.finish()
        }
    }
}

class MainActivity : Activity() {
    lateinit var sp: SharedPreferences
    lateinit var list: LinearLayout
    var h = 7
    var m = 0

    fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
    fun load(): JSONArray = JSONArray(sp.getString("t", "[]"))
    fun save(a: JSONArray) { sp.edit().putString("t", a.toString()).apply() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sp = getSharedPreferences("d", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 80, 40, 40)
        val title = TextView(this)
        title.text = "🎯 लक्ष्य साथी"
        title.textSize = 24f
        val et = EditText(this)
        et.hint = "रोज़ की क्रिया लिखें (जैसे: 45 मिनट व्यायाम)"
        val ck = CheckBox(this)
        ck.text = "यह मेरी रोज़ की क्रिया है, जो मेरे नियंत्रण में है"
        val tb = Button(this)
        tb.text = "समय: 07:00"
        tb.setOnClickListener {
            TimePickerDialog(this, { _, hh, mm ->
                h = hh; m = mm
                tb.text = "समय: %02d:%02d".format(hh, mm)
            }, h, m, true).show()
        }
        val add = Button(this)
        add.text = "जोड़ें"
        add.setOnClickListener {
            val s = et.text.toString().trim()
            if (s.isEmpty()) return@setOnClickListener
            if (!ck.isChecked) {
                AlertDialog.Builder(this)
                    .setMessage("यह परिणाम है, आपके नियंत्रण में नहीं। इसे रोज़ की क्रिया बनाइए, जैसे '75 किलो' की जगह 'रोज़ 45 मिनट व्यायाम'।")
                    .setPositiveButton("ठीक है", null).show()
                return@setOnClickListener
            }
            val id = System.currentTimeMillis()
            val a = load()
            a.put(JSONObject().put("i", id).put("x", s).put("h", h).put("m", m).put("d", 0))
            save(a)
            schedule(this, id, s, h, m)
            et.setText("")
            ck.isChecked = false
            render()
        }
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        root.addView(title); root.addView(et); root.addView(ck)
        root.addView(tb); root.addView(add); root.addView(list)
        val sv = ScrollView(this)
        sv.addView(root)
        setContentView(sv)
        val a = load()
        for (k in 0 until a.length()) {
            val o = a.getJSONObject(k)
            schedule(this, o.getLong("i"), o.getString("x"), o.getInt("h"), o.getInt("m"))
        }
        render()
    }

    fun render() {
        list.removeAllViews()
        val a = load()
        for (k in 0 until a.length()) {
            val o = a.getJSONObject(k)
            val row = LinearLayout(this)
            val cb = CheckBox(this)
            cb.text = "%02d:%02d  %s".format(o.getInt("h"), o.getInt("m"), o.getString("x"))
            cb.isChecked = o.getInt("d") == today()
            cb.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            cb.setOnCheckedChangeListener { _, c -> o.put("d", if (c) today() else 0); save(a) }
            val del = Button(this)
            del.text = "हटाएँ"
            del.setOnClickListener {
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(alarmPi(this, o.getLong("i"), o.getString("x"), o.getInt("h"), o.getInt("m")))
                a.remove(k); save(a); render()
            }
            row.addView(cb); row.addView(del)
            list.addView(row)
        }
    }
}
