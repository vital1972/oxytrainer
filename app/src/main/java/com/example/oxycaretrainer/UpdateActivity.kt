package com.example.oxycaretrainer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Проверка и установка обновлений из GitHub Releases. */
class UpdateActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var check: Button
    private lateinit var install: Button
    private var release: ReleaseInfo? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        supportActionBar?.setDisplayHomeAsUpEnabled(true); supportActionBar?.title = "Обновления"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(TextView(this).apply { text = "Установлена версия ${UpdateChecker.currentVersion(this@UpdateActivity)}"; textSize = 17f; setTypeface(typeface, Typeface.BOLD) })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(12), 0, dp(12)) }
        notes = TextView(this).apply { textSize = 13f; setTextColor(Color.DKGRAY) }
        check = Button(this).apply { text = "Проверить обновления"; setOnClickListener { checkNow() } }
        install = Button(this).apply { text = "Скачать и установить"; visibility = android.view.View.GONE; setOnClickListener { downloadAndInstall() } }
        root.addView(status); root.addView(check); root.addView(install); root.addView(notes)
        root.addView(TextView(this).apply {
            text = "Обновление скачивается с GitHub (${UpdateChecker.REPO}), проверяется контрольной суммой, а установку вы подтверждаете в системном окне. " +
                "Android принимает обновление только с той же подписью, что и у установленного приложения; при несовпадении подписи придётся сначала сохранить историю в файл (Настройки), удалить приложение и загрузить историю после установки."
            textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(20), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
        status.text = "Нажмите «Проверить обновления»."
    }

    private fun checkNow() {
        check.isEnabled = false; status.text = "Проверяю…"; install.visibility = android.view.View.GONE; notes.text = ""
        Thread {
            val result = runCatching { UpdateChecker.fetchLatest() }
            runOnUiThread {
                check.isEnabled = true
                result.onFailure { status.text = "Не удалось проверить: нет сети или GitHub недоступен." }
                result.onSuccess { info ->
                    release = info
                    when {
                        info == null -> status.text = "Опубликованных версий пока нет."
                        UpdateChecker.isUpdateAvailable(this, info) -> {
                            status.text = "Доступна версия ${info.version}."
                            notes.text = info.notes.take(1500)
                            install.visibility = android.view.View.VISIBLE
                        }
                        else -> status.text = "У вас последняя версия."
                    }
                }
            }
        }.start()
    }

    private fun downloadAndInstall() {
        val info = release ?: return
        install.isEnabled = false; check.isEnabled = false; status.text = "Скачиваю… 0%"
        Thread {
            val result = runCatching { UpdateChecker.download(this, info) { p -> runOnUiThread { status.text = "Скачиваю… $p%" } } }
            runOnUiThread {
                install.isEnabled = true; check.isEnabled = true
                result.onFailure {
                    DiagnosticLogger.error("update_download_failed", it)
                    status.text = "Не удалось скачать: ${it.message ?: "ошибка"}"
                }
                result.onSuccess { file ->
                    val opened = runCatching { UpdateChecker.install(this, file) }.getOrElse { false }
                    status.text = if (opened) "Подтвердите установку в системном окне." else "Разрешите приложению устанавливать обновления в настройках и нажмите кнопку ещё раз."
                }
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
