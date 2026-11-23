package com.musico.lyrics

import com.musico.BuildConfig
import com.musico.cloud.CloudHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class CloudLyricsService(
    httpClient: CloudHttpClient,
    appVersion: String = BuildConfig.VERSION_NAME,
    private val extensionSearch: (suspend (extensionId: String, query: CloudLyricsQuery) -> List<CloudLyricsCandidate>)? = null,
) {
    val lrclib = LrclibClient(httpClient, appVersion)
    val netease = NeteaseLyricsClient(httpClient)

    suspend fun search(
        query: CloudLyricsQuery,
        source: CloudLyricsSourceSelection,
        activeExtensions: List<CloudLyricsExtensionDescriptor>,
    ): CloudLyricsSearchResponse {
        if (query.isEmpty) throw CloudLyricsException.EmptyQuery()

        return when (source) {
            CloudLyricsSourceSelection.Lrclib -> {
                CloudLyricsSearchResponse(candidates = lrclib.search(query))
            }
            CloudLyricsSourceSelection.Netease -> {
                CloudLyricsSearchResponse(candidates = netease.search(query))
            }
            is CloudLyricsSourceSelection.Extension -> {
                val ext = activeExtensions.firstOrNull { it.id == source.extensionId }
                val searcher = extensionSearch
                if (ext == null || searcher == null) {
                    throw CloudLyricsException.ProviderUnavailable(source.extensionId)
                }
                val raw = searcher(ext.id, query)
                CloudLyricsSearchResponse(candidates = CloudLyricsScorer.rankAndDeduplicate(raw, query))
            }
            CloudLyricsSourceSelection.Auto -> searchAllSources(query, activeExtensions)
        }
    }

    private sealed interface TaskOutcome {
        data class Success(val list: List<CloudLyricsCandidate>) : TaskOutcome
        data class Failure(val failure: CloudLyricsSourceFailure) : TaskOutcome
    }

    private suspend fun searchAllSources(
        query: CloudLyricsQuery,
        activeExtensions: List<CloudLyricsExtensionDescriptor>,
    ): CloudLyricsSearchResponse = coroutineScope {

        val tasks = ArrayList<Deferred<TaskOutcome>>(2 + activeExtensions.size)
        tasks.add(
            async {
                try {
                    TaskOutcome.Success(lrclib.search(query))
                } catch (c: CancellationException) {
                    throw c
                } catch (ex: Exception) {
                    TaskOutcome.Failure(
                        CloudLyricsSourceFailure(
                            providerId = CloudLyricsProviderId.LRCLIB,
                            sourceName = "LRCLIB",
                            message = ex.message ?: "LRCLIB search failed",
                        )
                    )
                }
            }
        )
        tasks.add(
            async {
                try {
                    TaskOutcome.Success(netease.search(query))
                } catch (c: CancellationException) {
                    throw c
                } catch (ex: Exception) {
                    TaskOutcome.Failure(
                        CloudLyricsSourceFailure(
                            providerId = CloudLyricsProviderId.NETEASE,
                            sourceName = "NetEase",
                            message = ex.message ?: "NetEase search failed",
                        )
                    )
                }
            }
        )

        val searcher = extensionSearch
        if (searcher != null) {
            for (ext in activeExtensions) {
                tasks.add(
                    async {
                        try {
                            TaskOutcome.Success(searcher(ext.id, query))
                        } catch (c: CancellationException) {
                            throw c
                        } catch (ex: Exception) {
                            TaskOutcome.Failure(
                                CloudLyricsSourceFailure(
                                    providerId = "ext.${ext.id}",
                                    sourceName = ext.name,
                                    message = ex.message ?: "${ext.name} failed",
                                )
                            )
                        }
                    }
                )
            }
        }

        var successCount = 0
        val allCandidates = ArrayList<CloudLyricsCandidate>()
        val failures = ArrayList<CloudLyricsSourceFailure>()

        for (task in tasks) {
            when (val outcome = task.await()) {
                is TaskOutcome.Success -> {
                    successCount += 1
                    allCandidates.addAll(outcome.list)
                }
                is TaskOutcome.Failure -> {
                    failures.add(outcome.failure)
                }
            }
        }

        if (successCount == 0 && failures.isNotEmpty()) {
            throw CloudLyricsException.Network(failures.first().message)
        }

        CloudLyricsSearchResponse(
            candidates = CloudLyricsScorer.rankAndDeduplicate(allCandidates, query),
            failures = failures,
        )
    }

    companion object {
        fun sourceOptions(
            extensions: List<CloudLyricsExtensionDescriptor>,
            autoLabel: String,
        ): List<CloudLyricsSourceOption> {
            val options = ArrayList<CloudLyricsSourceOption>(3 + extensions.size)
            options.add(CloudLyricsSourceOption("auto", CloudLyricsSourceSelection.Auto, autoLabel))
            options.add(CloudLyricsSourceOption("lrclib", CloudLyricsSourceSelection.Lrclib, "LRCLIB"))
            options.add(CloudLyricsSourceOption("netease", CloudLyricsSourceSelection.Netease, "NetEase"))
            for (ext in extensions) {
                options.add(CloudLyricsSourceOption("ext.${ext.id}", CloudLyricsSourceSelection.Extension(ext.id), ext.name))
            }
            return options
        }
    }
}
