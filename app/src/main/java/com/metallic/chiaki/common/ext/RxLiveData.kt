// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import androidx.lifecycle.LiveData
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable

fun <T> Flowable<T>.toLiveData(): LiveData<T> = object : LiveData<T>() {
    private var disposable: Disposable? = null

    override fun onActive() {
        super.onActive()
        disposable = this@toLiveData.subscribe(
            { item -> postValue(item) },
            { /* ignore stream error in UI LiveData */ }
        )
    }

    override fun onInactive() {
        disposable?.dispose()
        disposable = null
        super.onInactive()
    }
}

fun <T> Observable<T>.toLiveData(): LiveData<T> =
    this.toFlowable(BackpressureStrategy.LATEST).toLiveData()

fun <T> Single<T>.toLiveData(): LiveData<T> =
    this.toFlowable().toLiveData()
