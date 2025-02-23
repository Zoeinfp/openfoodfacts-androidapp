package openfoodfacts.github.scrachx.openfood.network

import com.fasterxml.jackson.databind.JsonNode
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import openfoodfacts.github.scrachx.openfood.BuildConfig
import openfoodfacts.github.scrachx.openfood.network.services.WikidataAPI
import openfoodfacts.github.scrachx.openfood.utils.Utils
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.jackson.JacksonConverterFactory

/**
 * API client to recieve data from WikiData APIs
 *
 * @author Shubham Vishwakarma
 * @since 14.03.18
 */
class WikiDataApiClient
/**
 * Initializing the object of WikiDataApiService using the apiUrl
 *
 * @param apiUrl Url of the WikiData API
 */
(apiUrl: String = BuildConfig.WIKIDATA) {
    private val wikidataAPI = Retrofit.Builder()
            .baseUrl(apiUrl)
            .client(httpClient)
            .addConverterFactory(JacksonConverterFactory.create())
            .addCallAdapterFactory(RxJava2CallAdapterFactory.createWithScheduler(Schedulers.io()))
            .build()
            .create(WikidataAPI::class.java)

    /**
     * Get json response of the WikiData for additive/ingredient/category/label using their WikiDataID
     *
     * @param code WikiData ID of additive/ingredient/category/label
     */
    fun doSomeThing(code: String?): Single<JsonNode> {
        return wikidataAPI.getWikiCategory(code).observeOn(Schedulers.io()).map {
            it["entities"][code]
        }
    }

    companion object {
        private val httpClient = Utils.httpClientBuilder()
    }

}