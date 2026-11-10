package ai.teraq.roadguardian

/** 메인 스레드 상태. 숨겨졌거나 제한된 화면은 이전 개인 응답을 받지 않는다 */
class VehicleRecordState {
    var page: AccountPageResponse.Page? = null
        private set
    var detail: org.json.JSONObject? = null
        private set
    var error: String? = null
        private set
    var errorReason: AccountAccessException.Reason? = null
        private set
    var loading = false
        private set
    var requested = false
        private set
    var cursor: String? = null
        private set
    var category: String = "all"
        private set
    val previous = mutableListOf<String?>()
    private var generation = 0L
    private var lease: AccountDisplayLease? = null
    fun clear() {
        generation++
        page = null
        detail = null
        error = null
        errorReason = null
        loading = false
        requested = false
        cursor = null
        category = "all"
        previous.clear()
        lease = null
    }

    fun begin(nextCursor: String? = cursor, nextCategory: String = category): Long {
        require(nextCategory in setOf("all", "road", "maintenance", "incident"))
        if (nextCategory != category) previous.clear()
        generation++
        page = null
        detail = null
        error = null
        errorReason = null
        lease = null
        loading = true
        requested = true
        cursor = nextCursor
        category = nextCategory
        return generation
    }

    fun accept(
        ticket: Long,
        allowed: Boolean,
        page: AccountPageResponse.Page? = null,
        detail: org.json.JSONObject? = null,
        error: String? = null,
        lease: AccountDisplayLease? = null,
        errorReason: AccountAccessException.Reason? = null
    ): Boolean {
        if (ticket != generation) return false
        if (!allowed) {
            clear()
            return false
        }
        this.page = page
        this.detail = detail
        this.error = error
        this.errorReason = errorReason
        this.lease = lease
        loading = false
        return true
    }

    fun expire(wallNow: Long, elapsedNow: Long): Boolean {
        if (page == null && detail == null) return false
        if (lease?.valid(wallNow, elapsedNow) == true) return false
        clear()
        requested = true
        errorReason = AccountAccessException.Reason.EXPIRED
        error = AccountAccessException(AccountAccessException.Reason.EXPIRED).message
        return true
    }
}
