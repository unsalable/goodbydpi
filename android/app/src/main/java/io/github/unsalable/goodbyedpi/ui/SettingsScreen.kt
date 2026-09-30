package io.github.unsalable.goodbyedpi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.unsalable.goodbyedpi.BuildConfig
import io.github.unsalable.goodbyedpi.model.CustomDnsEntry
import io.github.unsalable.goodbyedpi.model.CustomMethodProfile
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.DpiConfig
import io.github.unsalable.goodbyedpi.model.FakePayload
import io.github.unsalable.goodbyedpi.model.IpLiterals
import io.github.unsalable.goodbyedpi.model.ThemeMode
import io.github.unsalable.goodbyedpi.ui.components.AccentButton
import io.github.unsalable.goodbyedpi.ui.components.Divider
import io.github.unsalable.goodbyedpi.ui.components.GdpiIconButton
import io.github.unsalable.goodbyedpi.ui.components.GdpiIcons
import io.github.unsalable.goodbyedpi.ui.components.GdpiSheet
import io.github.unsalable.goodbyedpi.ui.components.GdpiTextField
import io.github.unsalable.goodbyedpi.ui.components.GhostButton
import io.github.unsalable.goodbyedpi.ui.components.SectionLabel
import io.github.unsalable.goodbyedpi.ui.components.SegmentedChoice
import io.github.unsalable.goodbyedpi.ui.components.SelectorField
import io.github.unsalable.goodbyedpi.ui.components.StepperRow
import io.github.unsalable.goodbyedpi.ui.components.SurfaceCard
import io.github.unsalable.goodbyedpi.ui.components.ToggleRow
import io.github.unsalable.goodbyedpi.ui.components.enabledAlpha
import io.github.unsalable.goodbyedpi.ui.theme.GdpiTheme
import io.github.unsalable.goodbyedpi.ui.theme.GdpiType
import io.github.unsalable.goodbyedpi.ui.theme.Motion
import io.github.unsalable.goodbyedpi.ui.theme.rememberRefreshTransform
import io.github.unsalable.goodbyedpi.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Ayarlar ekrani (masaustundeki acilir ayar panelinin karsiligi). Bolumler tek bir tembel
 * listede: ekrana girmeyen bolumler kurulmuyor. Tum yazmalar ViewModel uzerinden
 * SettingsRepository'ye gider; calisan servis degisikligi kendisi uygular.
 */
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    settings: SettingsUi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = GdpiTheme.colors
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    var sheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }
    val listState = rememberLazyListState()

    // Govde kaydirilinca baslik altinda ayirici belirir (masaustu TitleDivider).
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val dividerAlpha by animateFloatAsState(if (scrolled) 1f else 0f, Motion.soft(), label = "divider")

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GdpiIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Geri", onBack, tint = c.text)
            Text(
                "Ayarlar",
                style = GdpiType.screenTitle,
                color = c.text,
                modifier = Modifier.padding(start = 4.dp).semantics { heading() },
            )
        }
        Divider(Modifier.graphicsLayer { alpha = dividerAlpha })

        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp + navBottom),
        ) {
            item(key = "isp") { IspSection(settings) { picker = Picker.Isp } }
            item(key = "method") { MethodSection(vm, settings) { picker = Picker.Method } }
            item(key = "dns") { DnsSection(vm, settings) { picker = Picker.Dns } }
            item(key = "general") { GeneralSection(vm, settings) }
            item(key = "background") { BackgroundSection() }
            item(key = "test") { ConnectionTestSection(vm) }
            item(key = "about") { AboutSection(vm) { sheet = it } }
        }
    }

    PickerHost(
        picker = picker,
        settings = settings,
        onSelectIsp = vm::selectIsp,
        onSelectMethod = vm::selectMethod,
        onSelectDns = vm::selectDns,
        onDismiss = { picker = null },
    )

    when (sheet) {
        SettingsSheet.Licenses -> LicensesSheet { sheet = null }
        SettingsSheet.Diagnostics -> DiagnosticsSheet(vm) { sheet = null }
        null -> Unit
    }
}

enum class SettingsSheet { Licenses, Diagnostics }

/** Secili ogenin aciklamasi; degisince yeni metin hafifce yukselerek gelir. */
@Composable
private fun Description(text: String, modifier: Modifier = Modifier) {
    val c = GdpiTheme.colors
    AnimatedContent(
        targetState = text,
        transitionSpec = rememberRefreshTransform(),
        modifier = modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 8.dp),
        label = "description",
    ) { t ->
        Text(t, style = GdpiType.rowHint, color = c.muted)
    }
}

@Composable
private fun Section(title: String, trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null) {
    SectionLabel(title, Modifier.padding(top = 20.dp, bottom = 4.dp).semantics { heading() }, trailing = trailing)
}

// ============================================================ saglayici

@Composable
private fun IspSection(settings: SettingsUi, onPick: () -> Unit) {
    Column {
        Section("İNTERNET SAĞLAYICI")
        SelectorField(settings.isp.name, onPick, "İnternet sağlayıcı: ${settings.isp.name}")
        Description(settings.isp.description)
    }
}

// ================================================================ yontem

@Composable
private fun MethodSection(vm: MainViewModel, settings: SettingsUi, onPick: () -> Unit) {
    // Kapanirken son profil gorunsun (AnimatedVisibility cikis animasyonu bos kutu gostermesin).
    val lastProfile = remember { mutableStateOf<CustomMethodProfile?>(null) }
    settings.customProfile?.let { lastProfile.value = it }

    Column {
        // Yeni profil listeden degil buradan olusturuluyor (masaustuyle ayni gerekce: listeye
        // konan bir "eylem" ogesi secim gibi davraniyor ve karisiyor).
        Section("YÖNTEM") {
            GhostButton("+ Yeni özel ayar", vm::addCustomProfile, Modifier.semantics { contentDescription = "Yeni özel ayar" })
        }
        SelectorField(settings.method.name, onPick, "Yöntem: ${settings.method.name}")
        Description(settings.method.description)

        AnimatedVisibility(
            visible = settings.customProfile != null,
            enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.spring()),
            exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.spring()),
            label = "customEditor",
        ) {
            lastProfile.value?.let { profile ->
                CustomProfileEditor(
                    profile = profile,
                    vm = vm,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun CustomProfileEditor(profile: CustomMethodProfile, vm: MainViewModel, modifier: Modifier = Modifier) {
    val c = GdpiTheme.colors
    val cfg = profile.config
    val id = profile.id
    val set: ((DpiConfig) -> DpiConfig) -> Unit = { t -> vm.updateCustomConfig(id, t) }
    // Kaydedilebilir: ekran donunce acik silme onayi kaybolmasin.
    var confirmDelete by rememberSaveable(id) { mutableStateOf(false) }

    SurfaceCard(modifier) {
        SectionLabel("PROFİL", color = c.accentText)
        // Ad her tus vurusunda degil, "Tamam", odak kaybi ya da kisa bir duraklamadan sonra
        // kaydedilir: her kayit diske yaziliyor ve bagliyken bildirim/durum metnini yeniliyor.
        val name = rememberDraft(id, profile.name, accept = { it.isNotBlank() }) { vm.renameCustomProfile(id, it) }
        val blank = name.text.isBlank()
        Row(verticalAlignment = Alignment.CenterVertically) {
            GdpiTextField(
                value = name.text,
                onValueChange = { name.text = it },
                label = "Profil adı",
                placeholder = "Profil adı",
                isError = blank,
                // Bos birakilip cikilirsa kayitli ad geri gelir: kutu ile listedeki ad ayrismasin.
                onFocusLost = { name.flush(restoreIfRejected = true) },
                modifier = Modifier.weight(1f),
            )
            GdpiIconButton(GdpiIcons.ContentCopy, "Profili çoğalt", vm::addCustomProfile)
            GdpiIconButton(Icons.Filled.Delete, "Profili sil", { confirmDelete = true }, tint = c.danger)
        }
        Text(
            if (blank) {
                "Ad boş olamaz; alandan çıkınca kayıtlı ad geri gelir."
            } else {
                "Yöntem listesinden istediğin kadar özel profil ekleyip aralarında geçiş yapabilirsin."
            },
            style = GdpiType.optionHint,
            color = if (blank) c.dangerText else c.muted,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )

        Divider(Modifier.padding(top = 14.dp, bottom = 4.dp))
        // Sifirlama geri alinabilir (alt cubukta "Geri al"): baslik satirina yakin bir yanlis
        // dokunus elle ayarlanmis profili kalici olarak silmesin.
        OptionGroup(
            title = "SAHTE PAKET",
            trailing = { GhostButton("Önerilene dön", { vm.resetCustomToRecommended(id) }) },
        ) {
            ToggleRow(
                "Sahte paket gönder", cfg.fakePacket, { v -> set { it.copy(fakePacket = v) } },
                hint = "Önce engelsiz bir siteye ait sahte istek gider",
            )
            ToggleRow(
                "Düşük TTL ile gönder", cfg.fakeTtl, { v -> set { it.copy(fakeTtl = v) } },
                hint = "Sahte paket sunucuya ulaşmadan yolda düşer",
                enabled = cfg.fakePacket,
            )
            StepperRow(
                "TTL değeri", cfg.ttl, DpiConfig.TTL_RANGE, { v -> set { it.copy(ttl = v) } },
                hint = "Sahte paket bu kadar yönlendirici sonra düşer",
                enabled = cfg.fakePacket && cfg.fakeTtl,
            )
            ToggleRow(
                "MD5 imzası (md5sig)", cfg.fakeMd5Sig, { v -> set { it.copy(fakeMd5Sig = v) } },
                hint = "Sunucu MD5 seçenekli paketi atar, DPI işler",
                enabled = cfg.fakePacket,
            )
            Column(Modifier.padding(vertical = 6.dp).enabledAlpha(cfg.fakePacket)) {
                Text("Sahte içerik", style = GdpiType.optionTitle, color = c.text)
                SegmentedChoice(
                    options = listOf(FakePayload.TLS to "Site isteği", FakePayload.ZEROS to "Boş (sıfır bayt)"),
                    selected = cfg.fakePayload,
                    onSelect = { p -> if (cfg.fakePacket) set { it.copy(fakePayload = p) } },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            FakeSniField(id, cfg, enabled = cfg.fakePacket && cfg.fakePayload == FakePayload.TLS) { sni ->
                set { it.copy(fakeSni = sni) }
            }
            ToggleRow(
                "Sahte isteği de böl", cfg.splitFake, { v -> set { it.copy(splitFake = v) } },
                hint = "Sahte istek iki parça halinde gider",
                enabled = cfg.fakePacket,
            )
            Warning(
                visible = cfg.fakePacket && !cfg.hasFakeProtection,
                text = "Koruma seçilmedi: sahte paket güvenlik için düşük TTL ile gönderilecek.",
            )
        }

        Divider(Modifier.padding(vertical = 4.dp))
        OptionGroup(title = "BÖLME") {
            ToggleRow(
                "İsteği parçalara böl", cfg.splitTls, { v -> set { it.copy(splitTls = v) } },
                hint = "TLS ClientHello birden çok TCP parçasıyla gider",
            )
            StepperRow(
                "Bölme konumu (bayt)", cfg.splitPosition, DpiConfig.SPLIT_RANGE, { v -> set { it.copy(splitPosition = v) } },
                hint = "İsteğin kaçıncı baytından bölüneceği",
                enabled = cfg.splitTls,
            )
            ToggleRow(
                "Site adının ortasından da böl", cfg.splitSni, { v -> set { it.copy(splitSni = v) } },
                hint = "SNI hiçbir parçada tam görünmez",
                enabled = cfg.splitTls,
            )
            ToggleRow(
                "Parçaları ters sırada gönder", cfg.reverseSplit, { v -> set { it.copy(reverseSplit = v) } },
                hint = "İlk parça geç ulaşır; segment birleştiremeyen DPI'ları aşar",
                enabled = cfg.splitTls,
            )
            ToggleRow(
                "TLS kayıt bölme", cfg.tlsRecordSplit, { v -> set { it.copy(tlsRecordSplit = v) } },
                hint = "İstek iki ayrı TLS kaydına bölünür (Android'e özel)",
            )
        }

        Divider(Modifier.padding(vertical = 4.dp))
        OptionGroup(title = "DİĞER") {
            ToggleRow(
                "QUIC (HTTP/3) engelle", cfg.blockQuic, { v -> set { it.copy(blockQuic = v) } },
                hint = "Uygulamalar denetlenebilen TCP bağlantısına geçer",
            )
            ToggleRow("HTTP (80) isteklerine de uygula", cfg.fragmentHttp, { v -> set { it.copy(fragmentHttp = v) } })
            ToggleRow(
                "Discord ses ve aramalar (UDP)", cfg.voiceFake, { v -> set { it.copy(voiceFake = v) } },
                hint = "Ses bağlantısından önce sahte UDP paketleri gider",
            )
            StepperRow(
                "Sahte UDP tekrar sayısı", cfg.voiceFakeRepeats, DpiConfig.REPEATS_RANGE,
                { v -> set { it.copy(voiceFakeRepeats = v) } },
                hint = "Her ses bağlantısından önce gönderilen paket sayısı",
                enabled = cfg.voiceFake,
            )
            Warning(
                visible = !cfg.fakePacket && !cfg.splitTls && !cfg.tlsRecordSplit,
                text = "Sahte paket ve bölme kapalı: motor yalnızca DNS, QUIC ve Discord ses ayarlarını uygular.",
            )
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "Profili sil",
            name = profile.name,
            onConfirm = {
                confirmDelete = false
                vm.deleteCustomProfile(id)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Sahte TLS isteginde gorunen engelsiz ad. Gecersiz yazim kaydedilmez (motor bosluklu ya da
 * garip bir adi arguman olarak alamaz); uyari gosterilir, gecerli hale gelince kaydedilir.
 */
@Composable
private fun FakeSniField(id: String, cfg: DpiConfig, enabled: Boolean, onSave: (String) -> Unit) {
    val c = GdpiTheme.colors
    // SNI motor komut satirinda: her gecerli on ek ("d", "di", ...) kaydedilseydi bagliyken
    // motor her duraklamada yeniden baslardi. "Onerilene don" gibi disaridan gelen degisiklik
    // ise kutuya yansir (rememberDraft).
    val draft = rememberDraft(id, cfg.fakeSni, accept = ::isValidHostName, commit = onSave)
    val valid = isValidHostName(draft.text.trim())
    Column(Modifier.padding(vertical = 6.dp).enabledAlpha(enabled)) {
        Text("Sahte site adı (SNI)", style = GdpiType.optionTitle, color = c.text)
        GdpiTextField(
            value = draft.text,
            onValueChange = { draft.text = it },
            label = "Sahte site adı",
            placeholder = DpiConfig.DEFAULT_FAKE_SNI,
            keyboardType = KeyboardType.Uri,
            isError = !valid,
            enabled = enabled,
            onFocusLost = { draft.flush() },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            if (valid) "Sahte istekte görünen, engelsiz bir site adı" else "Geçersiz ad: yalnızca harf, rakam, nokta ve tire.",
            style = GdpiType.optionHint,
            color = if (valid) c.muted else c.dangerText,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
    }
}

/** Ad/SNI alanlarinin kaydetme gecikmesi: yazarken her tus degil, duraklama kaydedilir. */
internal const val COMMIT_DELAY_MS = 800L

/**
 * Serbest metin alaninin yerel taslagi. Yazim yerelde kalir; ayara yalnizca [flush] ile gider:
 * klavyede "Tamam" / odak kaybi, ~800 ms duraklama ya da alan ekrandan kalkarken.
 *
 * Disaridan gelen degisiklik (ornek: "Onerilene don") kutuyu yeniden doldurur; kendi
 * kaydimizin geri yankisi ([lastSent]) ise doldurmaz, yoksa yazarken kutu eski bir on eke
 * geri sicrardi. Durum kaydedilebilir: ekran donunce yarim kalan (gecersiz) yazim kaybolmaz.
 */
@Stable
internal class Draft(text: String, var seen: String, var lastSent: String?) {
    var text by mutableStateOf(text)
    var accept: (String) -> Boolean = { true }
    var commit: (String) -> Unit = {}

    /** Gecerli ve kayitlidan farkliysa kaydeder; [restoreIfRejected] ise gecersizde kayitliya doner. */
    fun flush(restoreIfRejected: Boolean = false) {
        val v = text.trim()
        if (!accept(v)) {
            if (restoreIfRejected) text = seen
            return
        }
        if (v == seen || v == lastSent) return
        lastSent = v
        commit(v)
    }

    /** Ayardaki deger degisti: kendi kaydimizin yankisi degilse kutuya yansit. */
    fun onPersisted(value: String) {
        if (value == seen) return
        seen = value
        if (value == lastSent) {
            lastSent = null
            return
        }
        lastSent = null
        if (value != text.trim()) text = value
    }

    companion object {
        val Saver = listSaver<Draft, String>(
            save = { listOf(it.text, it.seen, it.lastSent ?: NONE) },
            restore = { Draft(it[0], it[1], it[2].takeUnless { v -> v == NONE }) },
        )
        private const val NONE = "\u0000"
    }
}

@Composable
internal fun rememberDraft(
    key: String,
    persisted: String,
    accept: (String) -> Boolean,
    commit: (String) -> Unit,
): Draft {
    val draft = rememberSaveable(key, saver = Draft.Saver) { Draft(persisted, persisted, null) }
    draft.accept = accept
    draft.commit = commit
    LaunchedEffect(draft, persisted) { draft.onPersisted(persisted) }
    // Her tus vurusu onceki bekleyisi iptal eder; yalnizca duraklama kaydedilir.
    LaunchedEffect(draft, draft.text) {
        delay(COMMIT_DELAY_MS)
        draft.flush()
    }
    // Geri tusu, duzenleyicinin kapanmasi ya da listeden kayma: odak kaybi gelmeyebilir.
    DisposableEffect(draft) { onDispose { draft.flush() } }
    return draft
}

/** DpiConfig.sanitized ile ayni kural: motora giden ad bosluk ya da kabuk karakteri tasimasin. */
internal fun isValidHostName(s: String): Boolean =
    s.isNotEmpty() && s.length <= 253 && !s.startsWith('.') && !s.endsWith('.') &&
        s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }

/**
 * Katlanabilir grup (SAHTE PAKET / BOLME / DIGER): baslik satirina dokununca icerik yay ile
 * kapanir/acilir, ok ters doner. Uzun duzenleyicide ilgilenilmeyen grup kapatilabilsin.
 */
@Composable
private fun OptionGroup(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = GdpiTheme.colors
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    val turn by animateFloatAsState(if (expanded) 0f else -90f, Motion.spring(), label = "chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Daralt" else "Genişlet") { expanded = !expanded }
            .semantics(mergeDescendants = false) { stateDescription = if (expanded) "Açık" else "Kapalı" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = c.accentText,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
        )
        Text(title, style = GdpiType.section, color = c.accentText, modifier = Modifier.padding(start = 6.dp).weight(1f))
        trailing?.invoke()
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.spring()),
        exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.spring()),
        label = "group",
    ) {
        Column(content = content)
    }
}

/** Kirmizi uyari satiri: kosul olusunca asagidan kayarak belirir (masaustu Motion.Reveal). */
@Composable
private fun Warning(visible: Boolean, text: String) {
    val c = GdpiTheme.colors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.spring()),
        exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.spring()),
        label = "warning",
    ) {
        Text(text, style = GdpiType.optionHint, color = c.dangerText, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = GdpiTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.muted,
        title = { Text(title, style = GdpiType.screenTitle) },
        text = { Text("\"$name\" silinsin mi? Bu işlem geri alınamaz.", style = GdpiType.rowHint) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sil", style = GdpiType.button, color = c.dangerText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç", style = GdpiType.button, color = c.muted) } },
    )
}

// =================================================================== DNS

@Composable
private fun DnsSection(vm: MainViewModel, settings: SettingsUi, onPick: () -> Unit) {
    val lastEntry = remember { mutableStateOf<CustomDnsEntry?>(null) }
    settings.customDns?.let { lastEntry.value = it }

    Column {
        Section("DNS") {
            GhostButton("+ Yeni DNS", vm::addCustomDns, Modifier.semantics { contentDescription = "Yeni DNS" })
        }
        SelectorField(settings.dns.name, onPick, "DNS: ${settings.dns.name}")
        Description(settings.dns.description)

        AnimatedVisibility(
            visible = settings.customDns != null,
            enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.spring()),
            exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.spring()),
            label = "dnsEditor",
        ) {
            lastEntry.value?.let { CustomDnsEditor(it, vm, Modifier.padding(top = 12.dp)) }
        }
    }
}

/**
 * Ozel DNS duzenleyicisi. Alanlar yerel tutulur ve her tus vurusunda dogrulanir (hata alanin
 * hemen altinda, kenarlik kirmizi). Kayit ise "Tamam", odak kaybi ya da kisa bir duraklamadan
 * sonra: adres motorun komut satirinda, bagliyken her gecerli ara yazim ("1.2.3.4" ->
 * "1.2.3.45") motoru yeniden baslatirdi. Gecersiz yazim kaydedilmez (masaustu DnsWarning).
 */
@Composable
private fun CustomDnsEditor(entry: CustomDnsEntry, vm: MainViewModel, modifier: Modifier = Modifier) {
    val c = GdpiTheme.colors
    val id = entry.id
    // Kaydedilebilir: ekran donunce yarim kalan (henuz gecersiz, kaydedilmemis) yazim kaybolmasin.
    var v4 by rememberSaveable(id) { mutableStateOf(entry.v4) }
    var p4 by rememberSaveable(id) { mutableStateOf(entry.v4Port.toString()) }
    var v6 by rememberSaveable(id) { mutableStateOf(entry.v6) }
    var p6 by rememberSaveable(id) { mutableStateOf(entry.v6Port.toString()) }
    var confirmDelete by rememberSaveable(id) { mutableStateOf(false) }
    val name = rememberDraft(id, entry.name, accept = { it.isNotBlank() }) { vm.renameCustomDns(id, it) }
    val nameBlank = name.text.isBlank()

    val warning = dnsWarning(v4, p4, v6, p6)
    val row4 = dnsRowWarning(v4, p4, v6 = false)
    val row6 = dnsRowWarning(v6, p6, v6 = true)
    val latest by rememberUpdatedState(entry)
    val commit: () -> Unit = {
        if (dnsWarning(v4, p4, v6, p6) == null) {
            val e = latest
            val port4 = p4.toIntOrNull() ?: 53
            val port6 = p6.toIntOrNull() ?: 53
            // Degismeyen deger yeniden yazilmaz (odak kaybi ve duraklama ayni degeri iki kez getirir).
            if (v4.trim() != e.v4 || port4 != e.v4Port || v6.trim() != e.v6 || port6 != e.v6Port) {
                vm.updateCustomDns(id, v4, port4, v6, port6)
            }
        }
    }
    val commitNow by rememberUpdatedState(commit)
    LaunchedEffect(id, v4, p4, v6, p6) {
        delay(COMMIT_DELAY_MS)
        commitNow()
    }
    DisposableEffect(id) { onDispose { commitNow() } }

    SurfaceCard(modifier) {
        SectionLabel("DNS PROFİLİ", color = c.accentText)
        Row(verticalAlignment = Alignment.CenterVertically) {
            GdpiTextField(
                value = name.text,
                onValueChange = { name.text = it },
                label = "DNS adı",
                placeholder = "DNS adı",
                isError = nameBlank,
                onFocusLost = { name.flush(restoreIfRejected = true) },
                modifier = Modifier.weight(1f),
            )
            GdpiIconButton(GdpiIcons.ContentCopy, "DNS girişini çoğalt", vm::addCustomDns)
            GdpiIconButton(Icons.Filled.Delete, "DNS girişini sil", { confirmDelete = true }, tint = c.danger)
        }
        if (nameBlank) {
            Text(
                "Ad boş olamaz; alandan çıkınca kayıtlı ad geri gelir.",
                style = GdpiType.optionHint,
                color = c.dangerText,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }

        Divider(Modifier.padding(top = 14.dp, bottom = 12.dp))
        SectionLabel("KENDİ DNS SUNUCUN", color = c.accentText)
        AddressBlock(
            address = v4,
            port = p4,
            addressLabel = "IPv4 adresi",
            placeholder = "örn. 8.8.8.8",
            hint = "IPv4 adresi ve port",
            rowWarning = row4,
            // Turkce klavyelerin sayi tusunda ondalik ayirici virgul olabiliyor: noktaya cevrilir.
            onAddress = { v4 = it.replace(',', '.') },
            onPort = { p4 = it },
            onFocusLost = commit,
            // Sayi klavyesi (Decimal) bazi klavyelerde tr-TR icin yalnizca ',' sunuyor; Uri her
            // klavyede '.' veriyor (IPv6 alani da ayni).
            keyboardType = KeyboardType.Uri,
        )
        AddressBlock(
            address = v6,
            port = p6,
            addressLabel = "IPv6 adresi",
            placeholder = "isteğe bağlı",
            hint = "IPv6 adresi ve port (isteğe bağlı)",
            rowWarning = row6,
            onAddress = { v6 = it },
            onPort = { p6 = it },
            onFocusLost = commit,
            modifier = Modifier.padding(top = 10.dp),
        )

        // Satirlara ait olmayan uyari (satirlar temizken) en altta; ayni metin iki kez gorunmesin.
        val general = warning.takeIf { row4 == null && row6 == null }
        val shown = remember { mutableStateOf("") }
        if (general != null) shown.value = general
        AnimatedVisibility(
            visible = general != null,
            enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.spring()),
            exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.spring()),
            label = "dnsWarning",
        ) {
            Text(shown.value, style = GdpiType.optionHint, color = c.dangerText, modifier = Modifier.padding(top = 10.dp))
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "DNS girişini sil",
            name = entry.name,
            onConfirm = {
                confirmDelete = false
                vm.deleteCustomDns(id)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * Adres + port satiri ve altindaki aciklama. Satirda hata varsa aciklamanin yerine hata
 * metni gelir. Kutulardan biri odaktayken blogun TAMAMI (aciklama/hata satiri dahil, altinda
 * [KEYBOARD_GAP] payla) klavyenin ustune kaydirilir: metin alaninin kendi kaydirmasi yalnizca
 * kutuyu gosteriyor, alttaki satir klavyenin altinda ya da kenarina yapisik kaliyordu.
 */
@Composable
private fun AddressBlock(
    address: String,
    port: String,
    addressLabel: String,
    placeholder: String,
    hint: String,
    rowWarning: DnsRowWarning?,
    onAddress: (String) -> Unit,
    onPort: (String) -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    val c = GdpiTheme.colors
    val density = LocalDensity.current
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var blockSize by remember { mutableStateOf(IntSize.Zero) }
    // Klavye acilirken her karede degisir; efekt her degisimde yeniden basladigi icin asagidaki
    // bekleme bir "durulma" beklemesi olur: kaydirma klavye yerine oturunca bir kez yapilir.
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(focused, rowWarning, imeBottom) {
        if (!focused) return@LaunchedEffect
        delay(120)
        val gap = with(density) { KEYBOARD_GAP.toPx() }
        requester.bringIntoView(Rect(0f, 0f, blockSize.width.toFloat(), blockSize.height + gap))
    }
    Column(
        modifier
            .bringIntoViewRequester(requester)
            .onSizeChanged { blockSize = it }
            .onFocusChanged { focused = it.hasFocus },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GdpiTextField(
                value = address,
                onValueChange = { onAddress(it.trim()) },
                label = addressLabel,
                placeholder = placeholder,
                keyboardType = keyboardType,
                isError = rowWarning?.address == true,
                onFocusLost = onFocusLost,
                modifier = Modifier.weight(1f),
            )
            GdpiTextField(
                value = port,
                // Yalnizca rakam; 5 haneden uzun port olamaz.
                onValueChange = { t -> onPort(t.filter(Char::isDigit).take(5)) },
                label = "$addressLabel portu",
                placeholder = "53",
                keyboardType = KeyboardType.Number,
                textAlign = TextAlign.Center,
                isError = rowWarning?.port == true,
                onFocusLost = onFocusLost,
                modifier = Modifier.width(92.dp),
            )
        }
        Text(
            rowWarning?.message ?: hint,
            style = GdpiType.optionHint,
            color = if (rowWarning != null) c.dangerText else c.muted,
            modifier = Modifier.padding(start = 2.dp, top = 4.dp),
        )
    }
}

/** Odaktaki adres blogunun alt kenari ile klavye arasinda birakilan bosluk. */
private val KEYBOARD_GAP = 16.dp

/** Tek satirin (adres + port) hatasi: hangi kutu kirmizi olacak ve ne yazacak. */
internal data class DnsRowWarning(val message: String, val address: Boolean, val port: Boolean)

/** Satir hatasi; bos adres hata degil (IPv6 istege bagli, ikisi de bossa genel uyari var). */
internal fun dnsRowWarning(address: String, port: String, v6: Boolean): DnsRowWarning? {
    val a = address.trim()
    val addressMessage = if (a.isNotEmpty() && !(if (v6) IpLiterals.isIpv6(a) else IpLiterals.isIpv4(a))) {
        if (v6) "Geçersiz IPv6 adresi." else "Geçersiz IPv4 adresi."
    } else {
        null
    }
    val portMessage = when {
        port.isBlank() -> null
        port.toIntOrNull() == null -> "Port bir sayı olmalı."
        port.toInt() !in 0..65535 -> "Port 0-65535 aralığında olmalı."
        else -> null
    }
    // Iki kutu da hataliysa ikisi de kirmizi; metin once adresi soyler (masaustu sirasi).
    val message = addressMessage ?: portMessage ?: return null
    return DnsRowWarning(message, address = addressMessage != null, port = portMessage != null)
}

/** Masaustu SetDnsPort + DnsProfile.Validate sirasiyla; hata yoksa null. */
internal fun dnsWarning(v4: String, p4: String, v6: String, p6: String): String? {
    val port4 = if (p4.isBlank()) 53 else p4.toIntOrNull() ?: return "Port bir sayı olmalı."
    val port6 = if (p6.isBlank()) 53 else p6.toIntOrNull() ?: return "Port bir sayı olmalı."
    return DnsProfile.createCustom("custom", "", v4, port4, v6, port6).validate()
}

// ================================================================= genel

@Composable
private fun GeneralSection(vm: MainViewModel, settings: SettingsUi) {
    val c = GdpiTheme.colors
    Column {
        Section("GENEL")
        Text("Tema", style = GdpiType.optionTitle, color = c.text, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        SegmentedChoice(
            options = listOf(ThemeMode.SYSTEM to "Sistem", ThemeMode.LIGHT to "Açık", ThemeMode.DARK to "Koyu"),
            selected = settings.themeMode,
            onSelect = vm::setTheme,
        )
        Spacer8()
        ToggleRow(
            "Otomatik bağlan", settings.autoConnect, { v -> vm.updateSettings { it.copy(autoConnect = v) } },
            hint = "Uygulama açılınca bağlantıyı başlatır (VPN izni verildiyse)",
            titleStyle = GdpiType.rowTitle, hintStyle = GdpiType.rowHint,
        )
        ToggleRow(
            "Açılışta başlat", settings.startOnBoot, { v -> vm.updateSettings { it.copy(startOnBoot = v) } },
            hint = "Telefon açılınca, bağlantı açık bırakıldıysa geri getirir",
            titleStyle = GdpiType.rowTitle, hintStyle = GdpiType.rowHint,
        )
        ToggleRow(
            "Otomatik güncelle", settings.autoUpdate, { v -> vm.updateSettings { it.copy(autoUpdate = v) } },
            // Davranisla ayni: acilista ve arka planda (~6 saatte bir) GitHub'a bakar, yeni surumu
            // indirip dogrular ve kurar (UpdateManager.onAppOpen / UpdateJobService).
            hint = "Yeni sürümü GitHub'dan kendisi indirir ve kurar (açılışta ve ~6 saatte bir)",
            titleStyle = GdpiType.rowTitle, hintStyle = GdpiType.rowHint,
        )
        ToggleRow(
            // Kapaliyken 1.0.0 davranisi: secili yontem her TLS/HTTP baglantisina uygulanir.
            "Akıllı mod", settings.smartMode, { v -> vm.updateSettings { it.copy(smartMode = v) } },
            hint = "Engelsiz sitelere dokunmaz; yalnızca engellenen bağlantılarda atlatma yöntemini kullanır",
            titleStyle = GdpiType.rowTitle, hintStyle = GdpiType.rowHint,
        )
        ToggleRow(
            "Otomatik yedek yöntem", settings.autoFallback, { v -> vm.updateSettings { it.copy(autoFallback = v) } },
            hint = "Bir site takılırsa sağlayıcının diğer yöntemleriyle yeniden dener",
            titleStyle = GdpiType.rowTitle, hintStyle = GdpiType.rowHint,
        )
        ToggleRow(
            "Yerel ağı hariç tut", settings.excludeLan, { v -> vm.updateSettings { it.copy(excludeLan = v) } },
            hint = "Yazıcı, NAS, Chromecast gibi yerel cihazlar VPN dışında kalır",
            titleStyle = GdpiType.rowTitle, hintStyle = GdpiType.rowHint,
        )
        ToggleRow(
            "IPv6", settings.ipv6, { v -> vm.updateSettings { it.copy(ipv6 = v) } },
            hint = "IPv6 trafiğini de tünelden geçirir",
            titleStyle = GdpiType.rowTitle, hintStyle = GdpiType.rowHint,
        )
    }
}

@Composable
private fun Spacer8() = Box(Modifier.height(8.dp))

// ============================================================= arka plan

@Composable
private fun BackgroundSection() {
    val context = LocalContext.current
    val c = GdpiTheme.colors
    // Kullanici sistem ekranindan donunce durum tazelensin.
    var ignoring by remember { mutableStateOf(SystemIntents.isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        ignoring = SystemIntents.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    Column {
        Section("ARKA PLAN")
        NavRow(
            title = "Pil optimizasyonunu kapat",
            hint = if (ignoring) {
                "Kapalı: Android bağlantıyı arka planda kapatmaz."
            } else {
                "Açık: bazı telefonlar bağlantıyı arka planda kapatabilir. Kapatmak için dokun."
            },
            value = if (ignoring) "Kapalı" else "Açık",
            valueColor = if (ignoring) c.successText else c.dangerText,
            onClick = { SystemIntents.openBatteryOptimization(context) },
        )
        NavRow(
            title = "Her zaman açık VPN",
            hint = "Android'in VPN ayarlarında GoodbyeDPI'ın yanındaki dişliye dokunup \"Her zaman açık VPN\"i " +
                "açarsan bağlantı telefon açılınca ve uygulama kapansa bile kendiliğinden kurulur.",
            onClick = { SystemIntents.openVpnSettings(context) },
        )
    }
}

/** Dokununca baska bir ekran acan satir: baslik, aciklama, sagda deger ya da ok. */
@Composable
private fun NavRow(
    title: String,
    onClick: (() -> Unit)?,
    hint: String? = null,
    value: String? = null,
    valueColor: androidx.compose.ui.graphics.Color = GdpiTheme.colors.muted,
    external: Boolean = false,
) {
    val c = GdpiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onClick)
                } else {
                    Modifier.semantics(mergeDescendants = true) { }
                },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = GdpiType.rowTitle, color = c.text)
            if (hint != null) Text(hint, style = GdpiType.rowHint, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) {
            AnimatedContent(targetState = value, transitionSpec = rememberRefreshTransform(), label = "navValue") {
                Text(it, style = GdpiType.button, color = valueColor, maxLines = 1)
            }
        }
        if (onClick != null) {
            Icon(
                if (external) GdpiIcons.OpenInNew else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.muted,
                modifier = Modifier.padding(start = 6.dp).size(if (external) 18.dp else 24.dp),
            )
        }
    }
}

// ======================================================= baglanti testi

@Composable
private fun ConnectionTestSection(vm: MainViewModel) {
    val c = GdpiTheme.colors
    val test by vm.connTest.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val viaProxy = connection.socksPort != null

    Column {
        Section("BAĞLANTI TESTİ")
        Text(
            if (viaProxy) {
                "Siteler çalışan motor üzerinden denenir."
            } else {
                "Bağlantı kapalı: siteler doğrudan denenir. Atlatmayı ölçmek için önce bağlan."
            },
            style = GdpiType.rowHint,
            color = c.muted,
        )
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentButton(
                text = if (test.running) "Test ediliyor…" else "Testi başlat",
                onClick = vm::runConnectionTest,
                enabled = !test.running,
            )
            AnimatedVisibility(visible = test.running, enter = fadeIn(), exit = fadeOut(), label = "spinner") {
                CircularProgressIndicator(
                    color = c.accent,
                    trackColor = c.track,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.padding(start = 14.dp).size(22.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = test.results.isNotEmpty(),
            enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.spring()),
            exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.spring()),
            label = "results",
        ) {
            SurfaceCard(Modifier.padding(top = 12.dp)) {
                Text(
                    if (test.viaProxy) "Motor üzerinden" else "Doğrudan bağlantı",
                    style = GdpiType.chipLabel,
                    color = c.muted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                test.results.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) { },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (r.ok) "✓" else "✗", style = GdpiType.number, color = if (r.ok) c.successText else c.dangerText, modifier = Modifier.width(24.dp))
                        Text(r.host, style = GdpiType.optionTitle, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (r.ok) "${r.millis ?: 0} ms" else (r.error ?: "Hata"),
                            style = GdpiType.optionHint,
                            color = if (r.ok) c.muted else c.dangerText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

// =============================================================== hakkinda

@Composable
private fun AboutSection(vm: MainViewModel, onSheet: (SettingsSheet) -> Unit) {
    val context = LocalContext.current
    val c = GdpiTheme.colors
    val update by vm.updateState.collectAsStateWithLifecycle()
    val (updateValue, updateColor) = when (val u = update) {
        UpdateState.Checking -> "Denetleniyor…" to c.muted
        UpdateState.UpToDate -> "Güncel" to c.successText
        is UpdateState.Available -> "Yeni: ${u.info.version}" to c.accentText
        is UpdateState.Downloading -> "İndiriliyor" to c.accentText
        is UpdateState.Verifying -> "Doğrulanıyor" to c.accentText
        is UpdateState.Installing -> "Kuruluyor" to c.accentText
        is UpdateState.NeedsPermission -> "İzin gerekli" to c.dangerText
        // Surum bilgisi varsa denetim basarili olmus, indirme/kurulum takilmis.
        is UpdateState.Failed -> (if (u.info != null) "Güncellenemedi" else "Denetlenemedi") to c.dangerText
        UpdateState.Idle -> "" to c.muted
    }

    Column {
        Section("HAKKINDA")
        NavRow(title = "Sürüm", value = BuildConfig.VERSION_NAME, onClick = null)
        NavRow(
            title = "GitHub",
            hint = "github.com/unsalable/goodbydpi",
            external = true,
            onClick = { SystemIntents.openUrl(context, SystemIntents.GITHUB_URL) },
        )
        NavRow(
            title = "Güncellemeleri denetle",
            hint = (update as? UpdateState.Failed)?.message,
            value = updateValue.ifEmpty { null },
            valueColor = updateColor,
            onClick = vm::checkForUpdates,
        )
        NavRow(title = "Açık kaynak lisansları", onClick = { onSheet(SettingsSheet.Licenses) })
        NavRow(
            title = "Tanılama",
            hint = "Motorun çalışan (bağlı değilse çalıştıracağı) komut satırı",
            onClick = { onSheet(SettingsSheet.Diagnostics) },
        )
    }
}

/**
 * Acik kaynak lisanslari: assets/licenses altindaki her dosya bir satir; dokununca metin
 * ayni sayfada kayarak acilir. Dosyalar IO is parcaciginda okunur.
 */
@Composable
private fun LicensesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = GdpiTheme.colors
    val files by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.list("licenses")?.sorted().orEmpty().filter { it.endsWith(".txt") } }
                .getOrDefault(emptyList())
        }
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    GdpiSheet(onDismiss = onDismiss) {
        // Metin acikken geri hareketi once listeye doner; sayfayi ancak listede kapatir.
        BackHandler(enabled = open != null) { open = null }
        AnimatedContent(
            targetState = open,
            transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally(Motion.spring()) { w -> if (forward) w / 6 else -w / 6 } + fadeIn(Motion.fadeIn())) togetherWith
                    (slideOutHorizontally(Motion.spring()) { w -> if (forward) -w / 6 else w / 6 } + fadeOut(Motion.fadeOut()))
            },
            label = "licenses",
        ) { file ->
            if (file == null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
                    Text(
                        "Açık kaynak lisansları",
                        style = GdpiType.screenTitle,
                        color = c.text,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                    files.forEach { f ->
                        Box(Modifier.padding(horizontal = 12.dp)) {
                            NavRow(title = licenseTitle(f), onClick = { open = f })
                        }
                    }
                }
            } else {
                val text by produceState("", file) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
                            .getOrDefault("Lisans metni okunamadı.")
                    }
                }
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 16.dp)) {
                        GdpiIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Lisans listesine dön", { open = null }, tint = c.text)
                        Text(licenseTitle(file), style = GdpiType.screenTitle, color = c.text, modifier = Modifier.padding(start = 4.dp))
                    }
                    SelectionContainer(Modifier.weight(1f)) {
                        // Satirlar 80 sutunda elle kirilmis; telefonda her ikinci satir kisa kaliyordu.
                        // Paragraflar birlestirilir, orantili yaziyla okunur.
                        Text(
                            reflowLicense(text),
                            style = GdpiType.rowHint,
                            color = c.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "LICENSE-hev-socks5-tunnel.txt" -> "hev-socks5-tunnel"; toplu bildirim dosyasi okunur adla. */
private fun licenseTitle(file: String): String = when (file) {
    THIRD_PARTY_NOTICES -> "AndroidX, Jetpack Compose, Kotlin"
    else -> file.removePrefix("LICENSE-").removeSuffix(".txt")
}

private const val THIRD_PARTY_NOTICES = "THIRD-PARTY-NOTICES.txt"

/**
 * Elle kirilmis lisans metnini ekrana gore akitir: bos satirla ayrilan paragraflar korunur,
 * paragraf icindeki tek satir sonlari bosluga doner. Madde basi ("-", "*", "(a)", "1.") ve
 * girintisi derinlesen satirlar yeni satirda kalir ki listeler ve basliklar bozulmasin.
 */
internal fun reflowLicense(text: String): String {
    val out = StringBuilder()
    val paragraphs = text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n+"))
    for ((pi, para) in paragraphs.withIndex()) {
        if (pi > 0) out.append("\n\n")
        var lineStart = true
        for (raw in para.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!lineStart && startsNewLine(line)) out.append('\n')
            else if (!lineStart) out.append(' ')
            out.append(line)
            lineStart = false
        }
    }
    return out.toString().trim()
}

private val listMarker = Regex("^([-*•]|\\([a-z0-9]{1,3}\\)|[0-9]{1,2}[.)])\\s")

private fun startsNewLine(line: String): Boolean =
    listMarker.containsMatchIn(line) || line.startsWith("Copyright", ignoreCase = true)

/** Tanilama: motorun komut satiri, secilebilir ve kopyalanabilir. */
@Composable
private fun DiagnosticsSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = GdpiTheme.colors
    val text = remember { vm.diagnostics() }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1800)
            copied = false
        }
    }

    GdpiSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text("Tanılama", style = GdpiType.screenTitle, color = c.text)
            Text(
                "GoodbyeDPI ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                style = GdpiType.rowHint,
                color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            SurfaceCard(Modifier.padding(top = 12.dp)) {
                SelectionContainer {
                    Text(text, style = GdpiType.mono, color = c.text)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                GhostButton(
                    text = if (copied) "Kopyalandı" else "Kopyala",
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("GoodbyeDPI tanılama", text))
                        copied = true
                    },
                )
            }
        }
    }
}
