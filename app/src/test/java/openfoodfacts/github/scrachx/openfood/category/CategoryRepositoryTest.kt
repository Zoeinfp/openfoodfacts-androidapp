package openfoodfacts.github.scrachx.openfood.category

import com.google.common.truth.Truth
import io.reactivex.Single
import io.reactivex.observers.TestObserver
import openfoodfacts.github.scrachx.openfood.category.mapper.CategoryMapper
import openfoodfacts.github.scrachx.openfood.category.model.Category
import openfoodfacts.github.scrachx.openfood.category.network.CategoryNetworkService
import openfoodfacts.github.scrachx.openfood.category.network.CategoryResponse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import java.util.*

/**
 * Created by Abdelali Eramli on 01/01/2018.
 */
@RunWith(MockitoJUnitRunner::class)
class CategoryRepositoryTest {
    @Mock
    private lateinit var mapper: CategoryMapper

    @Mock
    private lateinit var networkService: CategoryNetworkService

    @Mock
    private lateinit var category: Category

    @Mock
    private lateinit var response: CategoryResponse
    private lateinit var repository: CategoryRepository

    @Before
    fun setup() {
        Mockito.`when`(mapper.fromNetwork(ArgumentMatchers.any())).thenReturn(Arrays.asList(category, category, category))
        Mockito.`when`(networkService.categories).thenReturn(Single.just(response))
        repository = CategoryRepository(networkService, mapper)
    }

    @Test
    fun retrieveAll_Success() {
        val testObserver = TestObserver<List<Category>>()
        repository.retrieveAll().subscribe(testObserver)
        testObserver.awaitTerminalEvent()
        val result = testObserver.values()[0]
        Truth.assertThat(result[0]).isEqualTo(category)
    }
}