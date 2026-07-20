package app.senpou.extension.es.ikigaimangas

import androidx.preference.PreferenceScreen
import keiyoushi.source.CustomUrlPreferences
import keiyoushi.utils.getPreferences
import kotlin.Long
import kotlin.String

private val customUrlPrefs: CustomUrlPreferences = CustomUrlPreferences(
      preferences = getPreferences(7_540_019_444_621_673_796L),
      defaultUrl = "https://visorikigai.gettocaboca.com",
      title = "URL base personalizada",
      dialogMessage = "Dejar en blanco para usar la predeterminada",
    )

internal class ExtensionGenerated : IkigaiMangas() {
  override val name: String
    get() = "Senpou Ikigai Mangas"

  override val lang: String
    get() = "es"

  override val id: Long
    get() = 7_540_019_444_621_673_796L

  override val baseUrl: String
    get() = customUrlPrefs.baseUrl

  override fun setupPreferenceScreen(screen: PreferenceScreen) {
    customUrlPrefs.setupPreferenceScreen(screen)
    super.setupPreferenceScreen(screen)
  }
}
