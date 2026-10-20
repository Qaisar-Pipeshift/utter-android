package io.github.qaisarpipeshift.utter.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.BuildConfig
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.net.await
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.PillTone
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.StatusPill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** The latest GitHub release, reduced to what About needs. */
private data class Release(val tag: String, val url: String)

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val tag: String, val url: String) : UpdateState
    data object Failed : UpdateState
}

/**
 * About (docs/spec.md §15.5, §16; docs/plan.md Task 14).
 *
 * "Check for update" makes exactly one GitHub API request, on tap, and opens the release page in the browser when
 * the tag differs from this build's version. It never polls and never downloads an APK — Obtainium tracks releases
 * (spec §11). What's new is bundled, so the screen works offline; licences credit the two projects the app borrows
 * from, and the privacy paragraph states the one-server rule in plain words.
 */
@Composable
fun AboutScreen(graph: Graph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val version = BuildConfig.VERSION_NAME
    val whatsNew = remember(context) {
        runCatching {
            context.resources.openRawResource(R.raw.whats_new).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    UtterScreen(title = stringResource(R.string.screen_about), onBack = onBack) {
        SectionCard(title = stringResource(R.string.about_section_version)) {
            Text(
                text = stringResource(R.string.about_version, version, BuildConfig.GIT_SHA),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        SectionCard(title = stringResource(R.string.about_section_whats_new)) {
            Text(
                text = whatsNew.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.about_section_update)) {
            Button(
                onClick = {
                    if (state !is UpdateState.Checking) {
                        state = UpdateState.Checking
                        scope.launch {
                            val release = fetchLatest(graph.http.client)
                            state = when {
                                release == null -> UpdateState.Failed
                                release.tag.removePrefix("v") == version -> UpdateState.UpToDate
                                else -> {
                                    openRelease(context, release.url)
                                    UpdateState.Available(release.tag, release.url)
                                }
                            }
                        }
                    }
                },
                enabled = state !is UpdateState.Checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state is UpdateState.Checking) {
                            R.string.about_checking
                        } else {
                            R.string.about_check_update
                        },
                    ),
                )
            }
            when (val current = state) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> StatusPill(
                    tone = PillTone.NEUTRAL,
                    text = stringResource(R.string.about_checking),
                    modifier = Modifier.padding(top = 10.dp),
                )

                UpdateState.UpToDate -> StatusPill(
                    tone = PillTone.OK,
                    text = stringResource(R.string.about_up_to_date),
                    modifier = Modifier.padding(top = 10.dp),
                )

                is UpdateState.Available -> {
                    StatusPill(
                        tone = PillTone.WARN,
                        text = stringResource(R.string.about_update_available, current.tag),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TextButton(onClick = { openRelease(context, current.url) }) {
                        Text(stringResource(R.string.about_open_release))
                    }
                }

                UpdateState.Failed -> StatusPill(
                    tone = PillTone.ERROR,
                    text = stringResource(R.string.about_check_failed),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        SectionCard(title = stringResource(R.string.about_section_licenses)) {
            Text(
                text = stringResource(R.string.about_license_whisper),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.about_license_handy),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard(title = stringResource(R.string.about_section_privacy)) {
            Text(
                text = stringResource(R.string.about_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One request, only when the user taps; null means the release could not be read. */
private suspend fun fetchLatest(client: OkHttpClient): Release? = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(LATEST_RELEASE_URL)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Utter")
        .get()
        .build()
    try {
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return@withContext null
            val root = JSONObject(response.body.string())
            val tag = root.optString("tag_name")
            val url = root.optString("html_url")
            if (tag.isBlank() || url.isBlank()) null else Release(tag, url)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }
}

private fun openRelease(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}.isSuccess

private const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/Qaisar-Pipeshift/utter-android/releases/latest"
