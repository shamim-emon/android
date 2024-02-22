package com.x8bit.bitwarden.data.autofill.manager

import android.view.autofill.AutofillManager
import app.cash.turbine.test
import com.x8bit.bitwarden.data.platform.base.FakeDispatcherManager
import com.x8bit.bitwarden.data.platform.manager.AppForegroundManager
import com.x8bit.bitwarden.data.platform.manager.model.AppForegroundState
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutofillActivityManagerTest {
    private val autofillManager: AutofillManager = mockk {
        every { hasEnabledAutofillServices() } answers { isAutofillEnabledAndSupported }
        every { isAutofillSupported } answers { isAutofillEnabledAndSupported }
        every { isEnabled } answers { isAutofillEnabledAndSupported }
        every { disableAutofillServices() } just runs
    }
    private val autofillEnabledManager: AutofillEnabledManager = AutofillEnabledManagerImpl()

    private val mutableAppForegroundStateFlow = MutableStateFlow(AppForegroundState.BACKGROUNDED)
    private val appForegroundManager: AppForegroundManager = mockk {
        every { appForegroundStateFlow } returns mutableAppForegroundStateFlow
    }

    // We will construct an instance here just to hook the various dependencies together internally
    @Suppress("unused")
    private val autofillActivityManager: AutofillActivityManager = AutofillActivityManagerImpl(
        autofillManager = autofillManager,
        appForegroundManager = appForegroundManager,
        autofillEnabledManager = autofillEnabledManager,
        dispatcherManager = FakeDispatcherManager(),
    )

    private var isAutofillEnabledAndSupported = false

    @Test
    fun `changes in app foreground status should update the AutofillEnabledManager as necessary`() =
        runTest {
            autofillEnabledManager.isAutofillEnabledStateFlow.test {
                assertFalse(awaitItem())

                // An update is received when both the autofill state and foreground state change
                isAutofillEnabledAndSupported = true
                mutableAppForegroundStateFlow.value = AppForegroundState.FOREGROUNDED
                assertTrue(awaitItem())

                // An update is not received when only the foreground state changes
                mutableAppForegroundStateFlow.value = AppForegroundState.BACKGROUNDED
                expectNoEvents()

                // An update is not received when only the autofill state changes
                isAutofillEnabledAndSupported = false
                expectNoEvents()

                // An update is received after both states have changed
                mutableAppForegroundStateFlow.value = AppForegroundState.FOREGROUNDED
                assertFalse(awaitItem())
            }
        }
}
