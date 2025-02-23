package openfoodfacts.github.scrachx.openfood.features.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.SearchView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import openfoodfacts.github.scrachx.openfood.BuildConfig
import openfoodfacts.github.scrachx.openfood.R
import openfoodfacts.github.scrachx.openfood.customtabs.CustomTabActivityHelper
import openfoodfacts.github.scrachx.openfood.databinding.ActivityProductBrowsingListBinding
import openfoodfacts.github.scrachx.openfood.features.adapters.ProductsRecyclerViewAdapter
import openfoodfacts.github.scrachx.openfood.features.listeners.CommonBottomListenerInstaller.install
import openfoodfacts.github.scrachx.openfood.features.listeners.CommonBottomListenerInstaller.selectNavigationItem
import openfoodfacts.github.scrachx.openfood.features.listeners.EndlessRecyclerViewScrollListener
import openfoodfacts.github.scrachx.openfood.features.listeners.RecyclerItemClickListener
import openfoodfacts.github.scrachx.openfood.features.scan.ContinuousScanActivity
import openfoodfacts.github.scrachx.openfood.features.shared.BaseActivity
import openfoodfacts.github.scrachx.openfood.models.Product
import openfoodfacts.github.scrachx.openfood.models.Search
import openfoodfacts.github.scrachx.openfood.models.entities.country.Country
import openfoodfacts.github.scrachx.openfood.network.OpenFoodAPIClient
import openfoodfacts.github.scrachx.openfood.repositories.ProductRepository
import openfoodfacts.github.scrachx.openfood.utils.*
import java.text.NumberFormat
import java.util.*

class ProductSearchActivity : BaseActivity() {
    private lateinit var client: OpenFoodAPIClient
    private var _binding: ActivityProductBrowsingListBinding? = null
    private val binding get() = _binding!!
    private var contributionType = 0
    private var disp: CompositeDisposable? = null

    /**
     * boolean to determine if image should be loaded or not
     */
    private var isLowBatteryMode = false
    private var mCountProducts = 0
    private lateinit var mSearchInfo: SearchInfo
    private var pageAddress = 1
    private var setupDone = false

    override fun onDestroy() {
        super.onDestroy()
        disp!!.dispose()
        _binding = null
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onCreate(newBase))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchMenuItem = menu.findItem(R.id.action_search)
        val searchView = searchMenuItem.actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                mSearchInfo.searchQuery = query
                mSearchInfo.searchType = SearchType.SEARCH
                newSearchQuery()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                return true
            }
        })
        searchMenuItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                supportActionBar?.title = ""
                finish()
                return true
            }
        })
        if (SearchType.CONTRIBUTOR == mSearchInfo.searchType) {
            val contributionItem = menu.findItem(R.id.action_set_type)
            contributionItem.isVisible = true
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        if (item.itemId == R.id.action_set_type) {
            val contributionTypes = arrayOf(
                    getString(R.string.products_added),
                    getString(R.string.products_incomplete),
                    getString(R.string.product_pictures_contributed),
                    getString(R.string.picture_contributed_incomplete),
                    getString(R.string.product_info_added),
                    getString(R.string.product_info_tocomplete)
            )
            MaterialDialog.Builder(this).apply {
                title(R.string.show_by)
                items(*contributionTypes)
                itemsCallback { _, _, position, _ ->
                    when (position) {
                        1, 2, 3, 4, 5 -> {
                            contributionType = position
                            newSearchQuery()
                        }
                        else -> {
                            contributionType = 0
                            newSearchQuery()
                        }
                    }
                }
            }.show()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disp = CompositeDisposable()
        _binding = ActivityProductBrowsingListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbarInclude.toolbar)

        // OnClick
        binding.buttonTryAgain.setOnClickListener { setup() }
        binding.addProduct.setOnClickListener { addProduct() }
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.textCountProduct.visibility = View.INVISIBLE

        // Get the search information (query, title, type) that we will use in this activity
        val extras = intent.extras
        if (extras != null) {
            mSearchInfo = extras.getParcelable(SEARCH_INFO) ?: SearchInfo.emptySearchInfo()
        } else if (Intent.ACTION_VIEW == intent.action) {
            // the user has entered the activity via a url
            val data = intent.data
            if (data != null) {
                val paths: Array<String?> = data.toString().split("/").toTypedArray()
                mSearchInfo = SearchInfo.emptySearchInfo()
                mSearchInfo.searchTitle = paths[4]
                mSearchInfo.searchQuery = paths[4]
                mSearchInfo.searchType = Objects.requireNonNull(SearchType.fromUrl(paths[3]))
                if (paths[3] == "cgi" && paths[4] != null && paths[4]!!.contains("search.pl")) {
                    mSearchInfo.searchTitle = data.getQueryParameter("search_terms")
                    mSearchInfo.searchQuery = data.getQueryParameter("search_terms")
                    mSearchInfo.searchType = SearchType.SEARCH
                }
            } else {
                Log.i(javaClass.simpleName, "No data was passed in with URL. Exiting.")
                finish()
            }
        } else {
            Log.e(javaClass.simpleName, "No data passed to the activity. Exiting.")
            finish()
        }
        newSearchQuery()

        // If Battery Level is low and the user has checked the Disable Image in Preferences , then set isLowBatteryMode to true
        if (Utils.isDisableImageLoad(this) && Utils.isBatteryLevelLow(this)) {
            isLowBatteryMode = true
        }
        selectNavigationItem(binding.navigationBottom.bottomNavigation, 0)
        install(this, binding.navigationBottom.bottomNavigation)
    }

    private fun setupHungerGames() {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        val actualCountryTag = sharedPref.getString(LocaleHelper.USER_COUNTRY_PREFERENCE_KEY, "")
        if ("" == actualCountryTag) {
            disp!!.add(ProductRepository.getInstance().getCountryByCC2OrWorld(LocaleHelper.getLocale().country)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe { mayCountry: Optional<Country> -> setupUrlHungerGames(if (mayCountry.isPresent) mayCountry.get().tag else "en:world") })
        } else {
            setupUrlHungerGames(actualCountryTag)
        }
    }

    private fun setupUrlHungerGames(countryTag: String?) {
        val url = Uri.parse("https://hunger.openfoodfacts.org/questions?type=${mSearchInfo.searchType.url}&value_tag=${mSearchInfo.searchQuery}&country=$countryTag")
        val builder = CustomTabsIntent.Builder()
        val customTabsIntent = builder.build()
        binding.btnHungerGames.visibility = View.VISIBLE
        binding.btnHungerGames.text = resources.getString(R.string.hunger_game_call_to_action, mSearchInfo.searchTitle)
        binding.btnHungerGames.setOnClickListener {
            CustomTabActivityHelper.openCustomTab(this, customTabsIntent, url, null)
        }
    }

    private fun newSearchQuery() {
        supportActionBar?.title = mSearchInfo.searchTitle
        when (mSearchInfo.searchType) {
            SearchType.BRAND -> {
                supportActionBar!!.setSubtitle(R.string.brand_string)
                setupHungerGames()
            }
            SearchType.LABEL -> {
                supportActionBar!!.subtitle = getString(R.string.label_string)
                setupHungerGames()
            }
            SearchType.CATEGORY -> {
                supportActionBar!!.subtitle = getString(R.string.category_string)
                setupHungerGames()
            }
            SearchType.COUNTRY -> supportActionBar?.setSubtitle(R.string.country_string)
            SearchType.ORIGIN -> supportActionBar?.setSubtitle(R.string.origin_of_ingredients)
            SearchType.MANUFACTURING_PLACE -> supportActionBar?.setSubtitle(R.string.manufacturing_place)
            SearchType.ADDITIVE -> supportActionBar?.setSubtitle(R.string.additive_string)
            SearchType.SEARCH -> supportActionBar?.setSubtitle(R.string.search_string)
            SearchType.STORE -> supportActionBar?.setSubtitle(R.string.store_subtitle)
            SearchType.PACKAGING -> supportActionBar?.setSubtitle(R.string.packaging_subtitle)
            SearchType.CONTRIBUTOR -> supportActionBar?.setSubtitle(getString(R.string.contributor_string))
            SearchType.ALLERGEN -> supportActionBar?.setSubtitle(getString(R.string.allergen_string))
            SearchType.INCOMPLETE_PRODUCT -> supportActionBar?.setTitle(getString(R.string.products_to_be_completed))
            SearchType.STATE -> {
                // TODO: 26/07/2020 use resources
                supportActionBar?.setSubtitle("State")
            }
            else -> Log.e("Products Browsing", "No match case found for " + mSearchInfo.searchType)
        }
        client = OpenFoodAPIClient(this@ProductSearchActivity, BuildConfig.OFWEBSITE)
        binding.progressBar.visibility = View.VISIBLE
        setup()
    }

    fun setup() {
        binding.offlineCloudLinearLayout.visibility = View.INVISIBLE
        binding.textCountProduct.visibility = View.INVISIBLE
        pageAddress = 1
        binding.noResultsLayout.visibility = View.INVISIBLE
        loadDataFromAPI()
    }

    /**
     * When no matching products are found in the database then noResultsLayout is displayed.
     * This method is called when the user clicks on the add photo button in the noResultsLayout.
     */
    private fun addProduct() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                MaterialDialog.Builder(this)
                        .title(R.string.action_about)
                        .content(R.string.permission_camera)
                        .neutralText(R.string.txtOk)
                        .onNeutral { _, _ -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), Utils.MY_PERMISSIONS_REQUEST_CAMERA) }
                        .show()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), Utils.MY_PERMISSIONS_REQUEST_CAMERA)
            }
        } else {
            val intent = Intent(this, ContinuousScanActivity::class.java)
            startActivity(intent)
        }
    }

    fun loadDataFromAPI() {
        val searchQuery = mSearchInfo.searchQuery
        when (mSearchInfo.searchType) {
            SearchType.BRAND -> startSearch(client.getProductsByBrand(searchQuery, pageAddress), R.string.txt_no_matching_brand_products)
            SearchType.COUNTRY -> startSearch(client.getProductsByCountry(searchQuery, pageAddress), R.string.txt_no_matching_country_products)
            SearchType.ORIGIN -> startSearch(client.getProductsByOrigin(searchQuery, pageAddress), R.string.txt_no_matching_country_products)
            SearchType.MANUFACTURING_PLACE -> startSearch(client.getProductsByManufacturingPlace(searchQuery, pageAddress), R.string.txt_no_matching_country_products)
            SearchType.ADDITIVE -> startSearch(client.getProductsByAdditive(searchQuery, pageAddress), R.string.txt_no_matching_additive_products)
            SearchType.STORE -> startSearch(client.getProductsByStore(searchQuery, pageAddress), R.string.txt_no_matching_store_products)
            SearchType.PACKAGING -> startSearch(client.getProductsByPackaging(searchQuery, pageAddress), R.string.txt_no_matching_packaging_products)
            SearchType.SEARCH -> {
                if (ProductUtils.isBarcodeValid(searchQuery)) {
                    client.openProduct(searchQuery, this)
                } else {
                    startSearch(client.searchProductsByName(searchQuery, pageAddress), R.string.txt_no_matching_products, R.string.txt_broaden_search)
                }
            }
            SearchType.LABEL -> startSearch(client.getProductsByLabel(searchQuery, pageAddress), R.string.txt_no_matching_label_products)
            SearchType.CATEGORY -> startSearch(client.getProductsByCategory(searchQuery, pageAddress), R.string.txt_no_matching__category_products)
            SearchType.ALLERGEN -> startSearch(client.getProductsByAllergen(searchQuery, pageAddress), R.string.txt_no_matching_allergen_products)
            SearchType.CONTRIBUTOR -> loadDataForContributor(searchQuery)
            SearchType.STATE -> startSearch(client.getProductsByStates(searchQuery, pageAddress), R.string.txt_no_matching_allergen_products)
            SearchType.INCOMPLETE_PRODUCT -> {
                // Get Products to be completed data and input it to loadData function
                startSearch(client.getIncompleteProducts(pageAddress), R.string.txt_no_matching_incomplete_products)
            }
            else -> Log.e("Products Browsing", "No match case found for " + mSearchInfo.searchType)
        }
    }

    private fun startSearch(searchSingle: Single<Search>, @StringRes noMatchTextRes: Int) {
        startSearch(searchSingle, noMatchTextRes, -1)
    }

    private fun startSearch(searchSingle: Single<Search>, @StringRes noMatchMsg: Int, @StringRes extendedMsg: Int) {
        disp!!.add(searchSingle
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { search: Search?, throwable: Throwable? -> displaySearch(throwable == null, search, noMatchMsg, extendedMsg) })
    }

    private fun loadDataForContributor(searchQuery: String) {
        when (contributionType) {
            1 -> client.getToBeCompletedProductsByContributor(searchQuery, pageAddress) { value, category ->
                displaySearch(value, category, R.string.txt_no_matching_contributor_products)
            }
            2 -> client.getPicturesContributedProducts(searchQuery, pageAddress) { value, category ->
                displaySearch(value, category, R.string.txt_no_matching_contributor_products)
            }
            3 -> client.getPicturesContributedIncompleteProducts(searchQuery, pageAddress) { value: Boolean, category: Search? ->
                displaySearch(value, category, R.string.txt_no_matching_contributor_products)
            }
            4 -> client.getInfoAddedProducts(searchQuery, pageAddress) { value, category ->
                displaySearch(value, category, R.string.txt_no_matching_contributor_products)
            }
            5 -> startSearch(client.getInfoAddedIncompleteProductsSingle(searchQuery, pageAddress), R.string.txt_no_matching_contributor_products)
            0 -> startSearch(client.getProductsByContributor(searchQuery, pageAddress), R.string.txt_no_matching_contributor_products)
            else -> startSearch(client.getProductsByContributor(searchQuery, pageAddress), R.string.txt_no_matching_contributor_products)
        }
    }

    private fun loadData(isResponseOk: Boolean, response: Search?) {
        val mProducts = mutableListOf<Product?>()
        if (isResponseOk && response != null) {
            mCountProducts = response.count.toInt()
            if (pageAddress == 1) {
                val number = NumberFormat.getInstance(Locale.getDefault())
                        .format(response.count.toLong())
                binding.textCountProduct.text = resources.getString(R.string.number_of_results) + number
                mProducts.addAll(response.products)
                if (mProducts.size < mCountProducts) {
                    mProducts.add(null)
                }
                if (setupDone) {
                    binding.productsRecyclerView.adapter = ProductsRecyclerViewAdapter(mProducts, isLowBatteryMode)
                }
                setUpRecyclerView(mProducts)
            } else {
                if (mProducts.size - 1 < mCountProducts + 1) {
                    val posStart = mProducts.size
                    mProducts.removeAt(mProducts.size - 1)
                    mProducts.addAll(response.products)
                    if (mProducts.size < mCountProducts) {
                        mProducts.add(null)
                    }
                    binding.productsRecyclerView.adapter?.notifyItemRangeChanged(posStart - 1, mProducts.size - 1)
                }
            }
        } else {
            binding.swipeRefresh.isRefreshing = false
            binding.productsRecyclerView.visibility = View.INVISIBLE
            binding.progressBar.visibility = View.INVISIBLE
            binding.offlineCloudLinearLayout.visibility = View.VISIBLE
        }
    }

    /**
     * Shows UI indicating that no matching products were found. Called by
     * [.displaySearch] and [.displaySearch]
     *
     * @param message message to display when there are no results for given search
     * @param extendedMessage additional message to display, -1 if no message is displayed
     */
    private fun showEmptySearch(@StringRes message: Int, @StringRes extendedMessage: Int) {
        binding.textNoResults.setText(message)
        if (extendedMessage != -1) {
            binding.textExtendSearch.setText(extendedMessage)
        }
        binding.noResultsLayout.visibility = View.VISIBLE
        binding.noResultsLayout.bringToFront()
        binding.productsRecyclerView.visibility = View.INVISIBLE
        binding.progressBar.visibility = View.INVISIBLE
        binding.offlineCloudLinearLayout.visibility = View.INVISIBLE
        binding.textCountProduct.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
    }

    /**
     * Loads the search results into the UI, otherwise shows UI indicating that no matching
     * products were found.
     *
     * @param isResponseSuccessful true if the search response was successful
     * @param response the search results
     * @param emptyMessage message to display if there are no results
     * @param extendedMessage extended message to display if there are no results
     */
    private fun displaySearch(isResponseSuccessful: Boolean, response: Search?,
                              @StringRes emptyMessage: Int, @StringRes extendedMessage: Int = -1) {
        if (response == null) {
            loadData(isResponseSuccessful, null)
        } else {
            val count = try {
                response.count.toInt()
            } catch (e: NumberFormatException) {
                throw NumberFormatException("Cannot parse ${response.count}.")
            }
            if (isResponseSuccessful && count == 0) {
                showEmptySearch(emptyMessage, extendedMessage)
            } else {
                loadData(isResponseSuccessful, response)
            }
        }
    }

    private fun setUpRecyclerView(mProducts: MutableList<Product?>) {
        binding.progressBar.visibility = View.INVISIBLE
        binding.swipeRefresh.isRefreshing = false
        binding.textCountProduct.visibility = View.VISIBLE
        binding.offlineCloudLinearLayout.visibility = View.INVISIBLE
        binding.productsRecyclerView.visibility = View.VISIBLE

        if (!setupDone) {
            binding.productsRecyclerView.setHasFixedSize(true)
            val mLayoutManager = LinearLayoutManager(this@ProductSearchActivity, LinearLayoutManager.VERTICAL, false)
            binding.productsRecyclerView.layoutManager = mLayoutManager
            val adapter = ProductsRecyclerViewAdapter(mProducts, isLowBatteryMode)
            binding.productsRecyclerView.adapter = adapter
            val dividerItemDecoration = DividerItemDecoration(binding.productsRecyclerView.context, DividerItemDecoration.VERTICAL)
            binding.productsRecyclerView.addItemDecoration(dividerItemDecoration)

            // Retain an instance so that you can call `resetState()` for fresh searches
            // Adds the scroll listener to RecyclerView
            binding.productsRecyclerView.addOnScrollListener(object : EndlessRecyclerViewScrollListener(mLayoutManager) {
                override fun onLoadMore(page: Int, totalItemsCount: Int, view: RecyclerView) {
                    if (mProducts.size < mCountProducts) {
                        pageAddress = page
                        loadDataFromAPI()
                    }
                }
            })
            binding.productsRecyclerView.addOnItemTouchListener(RecyclerItemClickListener(this) { _, position ->
                val product = (binding.productsRecyclerView.adapter as ProductsRecyclerViewAdapter?)?.getProduct(position)
                        ?: return@RecyclerItemClickListener
                val barcode = product.code
                if (Utils.isNetworkConnected(this@ProductSearchActivity)) {
                    client.openProduct(barcode, this@ProductSearchActivity)
                    try {
                        val viewWithFocus = this@ProductSearchActivity.currentFocus
                        if (viewWithFocus != null) {
                            val imm = this@ProductSearchActivity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.hideSoftInputFromWindow(viewWithFocus.windowToken, 0)
                        }
                    } catch (e: NullPointerException) {
                        Log.e(ProductSearchActivity::class.java.simpleName, "addOnItemTouchListener", e)
                    }
                } else {
                    MaterialDialog.Builder(this@ProductSearchActivity).apply {
                        title(R.string.device_offline_dialog_title)
                        content(R.string.connectivity_check)
                        positiveText(R.string.txt_try_again)
                        onPositive { _, _ ->
                            if (Utils.isNetworkConnected(this@ProductSearchActivity)) {
                                client.openProduct(barcode, this@ProductSearchActivity)
                            } else {
                                Toast.makeText(this@ProductSearchActivity, R.string.device_offline_dialog_title, Toast.LENGTH_SHORT).show()
                            }
                        }
                        negativeText(R.string.dismiss)
                        onNegative { dialog, _ -> dialog.dismiss() }
                    }.show()
                }
                return@RecyclerItemClickListener
            })
            binding.swipeRefresh.setOnRefreshListener {
                mProducts.clear()
                adapter.notifyDataSetChanged()
                binding.textCountProduct.text = resources.getString(R.string.number_of_results)
                pageAddress = 1
                setup()
                if (binding.swipeRefresh.isRefreshing) {
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
        setupDone = true
        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = true
            pageAddress = 1
            setup()
        }
    }

    companion object {
        /**
         * Must be public to be visible by TakeScreenshotIncompleteProductsTest class.
         */
        const val SEARCH_INFO = "search_info"

        /**
         * Start a new [ProductSearchActivity] given a search information
         *
         * @param context the context to use to start this activity
         * @param searchQuery the search query
         * @param searchTitle the title used in the activity for this search query
         * @param type the type of search
         */
        @JvmStatic
        fun start(context: Context, searchQuery: String?, searchTitle: String?, type: SearchType?) {
            start(context, SearchInfo(searchQuery, searchTitle, type))
        }

        /**
         * @see [start]
         */
        @JvmStatic
        fun start(context: Context, searchQuery: String?, type: SearchType?) {
            start(context, searchQuery, searchQuery, type)
        }

        /**
         * @see [start]
         */
        @JvmStatic
        private fun start(context: Context, searchInfo: SearchInfo) {
            val intent = Intent(context, ProductSearchActivity::class.java)
            intent.putExtra(SEARCH_INFO, searchInfo)
            context.startActivity(intent)
        }
    }
}