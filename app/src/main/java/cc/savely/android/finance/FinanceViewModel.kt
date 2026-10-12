package cc.savely.android.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class FinanceUiState(
    val data: FinanceData? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val updatedAt: Instant? = null,
    val message: String? = null,
    val positionCloseFailure: PositionCloseException? = null,
    val reconciliationRequired: Boolean = false,
)

class FinanceViewModel(private val repository: FinanceRepository, private val section: String) : ViewModel() {
    private val mutableState = MutableStateFlow(FinanceUiState())
    val state: StateFlow<FinanceUiState> = mutableState
    private var refreshJob: Job? = null
    private val writeBusy = AtomicBoolean(false)

    fun refresh() {
        if (writeBusy.get() || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                val data = repository.load(section)
                mutableState.value = mutableState.value.copy(data = data, loading = false, updatedAt = Instant.now(),
                    reconciliationRequired = false, positionCloseFailure = null)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(loading = false,
                    error = exception.message ?: "Impossibile caricare i dati. Controlla la rete e riprova.")
            }
        }
    }

    fun mutate(operation: suspend FinanceRepository.() -> Unit, onSuccess: () -> Unit) {
        if (mutableState.value.reconciliationRequired) return
        if (!writeBusy.compareAndSet(false, true)) return
        refreshJob?.cancel()
        mutableState.value = mutableState.value.copy(saving = true, loading = false, error = null, message = null, positionCloseFailure = null)
        viewModelScope.launch {
            var succeeded = false
            try {
                repository.operation()
                mutableState.value = mutableState.value.copy(saving = false)
                onSuccess()
                succeeded = true
            } catch (exception: CancellationException) {
                mutableState.value = mutableState.value.copy(saving = false, reconciliationRequired = true,
                    error = "Operazione interrotta. Aggiorna i dati e verifica le righe prima di continuare.")
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(saving = false,
                    error = exception.message ?: "Operazione fallita. Controlla la connessione e riprova.",
                    positionCloseFailure = exception as? PositionCloseException,
                    reconciliationRequired = exception is PositionCloseException || exception is TransactionSharingException ||
                        exception is FinancePendingRefreshException || exception is RecurringProcessingException)
            } finally {
                writeBusy.set(false)
            }
            if (succeeded) refresh()
        }
    }

    fun setRecurringActive(id: String, active: Boolean) = mutate({ setRecurringActive(id, active) }) {}

    fun closePositions(assets: List<FinanceAsset>, date: String, price: String, onSuccess: () -> Unit) =
        mutate({ closeAssetGroup(assets, date, price) }) {
            // Confirmed closures also leave the old list stale until the subsequent read succeeds.
            mutableState.value = mutableState.value.copy(reconciliationRequired = true)
            onSuccess()
        }

    fun processDueRecurring() {
        var result: RecurringProcessingResult? = null
        mutate({ result = processDueRecurring() }) {
            mutableState.value = mutableState.value.copy(message = result?.message)
        }
    }

    fun dismissRecurringSuggestion(key: String) {
        val data = mutableState.value.data ?: return
        if (mutableState.value.loading || availableRecurringSuggestions(data).none { it.key == key } || !writeBusy.compareAndSet(false, true)) return
        mutableState.value = mutableState.value.copy(saving = true, error = null, message = null)
        viewModelScope.launch {
            try {
                repository.dismissRecurringSuggestion(key)
                mutableState.value = mutableState.value.copy(saving = false,
                    data = data.copy(ignoredRecurringSuggestions = data.ignoredRecurringSuggestions + key))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.value = mutableState.value.copy(saving = false, error = exception.message ?: "Impossibile ignorare il suggerimento")
            } finally {
                writeBusy.set(false)
            }
        }
    }
}
