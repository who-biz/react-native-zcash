package app.edge.rnzcash;

import cash.z.ecc.android.sdk.Initializer
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.db.entity.*
import cash.z.ecc.android.sdk.ext.*
import cash.z.ecc.android.sdk.internal.service.LightWalletGrpcService
import cash.z.ecc.android.sdk.internal.*
import cash.z.ecc.android.sdk.model.*
import cash.z.ecc.android.sdk.type.*
import cash.z.ecc.android.sdk.tool.DerivationTool
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import kotlin.coroutines.EmptyCoroutineContext

class RNZcashModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    /**
     * Scope for anything that out-lives the synchronizer, meaning anything that can be used before
     * the synchronizer starts or after it stops. Everything else falls within the scope of the
     * synchronizer and should use `synchronizer.coroutineScope` whenever a scope is needed.
     *
     * In a real production app, we'd use the scope of the parent activity
     */
    var moduleScope: CoroutineScope = CoroutineScope(EmptyCoroutineContext)
    var synchronizerMap = mutableMapOf<String, SdkSynchronizer>()

    val networks = mapOf("mainnet" to ZcashNetwork.Mainnet, "testnet" to ZcashNetwork.Testnet)

    override fun getName() = "RNZcash"

    @ReactMethod
    fun initialize(extfvk: String, extpub: String, birthdayHeight: Int, alias: String, networkName: String = "mainnet", defaultHost: String = "mainnet.lightwalletd.com", defaultPort: Int = 9067, promise: Promise) =
        promise.wrap {
          Twig.plant(TroubleshootingTwig())
          var vk = UnifiedViewingKey(extfvk, extpub)
          var network = networks.getOrDefault(networkName, ZcashNetwork.Mainnet)
          var endpoint = LightWalletEndpoint(defaultHost, defaultPort, true)
          if (synchronizerMap[alias] == null) {
            runBlocking {
              Initializer.new(reactApplicationContext) {
                it.importedWalletBirthday(BlockHeight.new(network, birthdayHeight.toLong()))
                it.setViewingKeys(vk)
                it.setNetwork(network, endpoint)
                it.alias = alias
              }
            }.let { initializer ->
              synchronizerMap[alias] = Synchronizer.newBlocking(
                initializer
            ) as SdkSynchronizer
            }
          }
        }

    @ReactMethod
    fun start(alias: String, promise: Promise) = promise.wrap {
        val wallet = getWallet(alias)
        if (!wallet.isStarted) {
            runBlocking {
              wallet.prepare()
            }
            wallet.start(moduleScope)
            val scope = wallet.coroutineScope
            wallet.processorInfo.collectWith(scope, { update ->
                sendEvent("UpdateEvent") { args ->
                    args.putString("alias", alias)
                    args.putInt("lastDownloadedHeight", update.lastDownloadedHeight.toString().toInt())
                    args.putInt("lastScannedHeight", update.lastScannedHeight.toString().toInt())
                    args.putInt("scanProgress", update.scanProgress)
                    args.putInt("networkBlockHeight", update.networkBlockHeight.toString().toInt())
                }
            })
            wallet.status.collectWith(scope, { status ->
                sendEvent("StatusEvent") { args ->
                    args.putString("alias", alias)
                    args.putString("name", status.toString())
                }
            })
            wallet.saplingBalances.collectWith(scope, { walletBalance ->
                sendEvent("BalanceEvent") { args ->
                    args.putString("alias", alias)
                    args.putString("availableZatoshi", walletBalance?.available.toString())
                    args.putString("totalZatoshi", walletBalance?.total.toString())
                }
            })
            // add 'distinctUntilChanged' to filter by events that represent changes in txs, rather than each poll
            wallet.clearedTransactions.distinctUntilChanged().collectWith(scope, { txList ->
                sendEvent("TransactionEvent") { args ->
                    args.putString("alias", alias)
                    args.putBoolean("hasChanged", true)
                    args.putInt("transactionCount", txList.count())
                }
            })
            wallet.isStarted = true
        }
        return@wrap
    }

    @ReactMethod
    fun stop(alias: String, promise: Promise) = promise.wrap {
        val wallet = getWallet(alias)
        wallet.stop()
        synchronizerMap.remove(alias)
        return@wrap
    }

    @ReactMethod
    fun getTransactions(alias: String, first: Int, last: Int, promise: Promise) {
        val wallet = getWallet(alias)
        moduleScope.launch {
            promise.wrap {
                val result = runBlocking { wallet.clearedTransactions.flatMapConcat { it.asFlow() }.takeWhile { it.minedHeight >= first.toLong() }
                .filter { it.minedHeight <= last.toLong() }
                .toList() }
                val nativeArray = Arguments.createArray()

                for (i in 0..result.size - 1) {
                    val map = Arguments.createMap()
                    map.putString("value", result[i].value.toString())
                    map.putInt("minedHeight", result[i].minedHeight.toString().toInt())
                    map.putInt("blockTimeInSeconds", result[i].blockTimeInSeconds.toInt())
                    map.putString("rawTransactionId", result[i].rawTransactionId.toHexReversed())
                    if (result[i].memo != null) map.putString("memo", result[i].memo?.decodeToString()?.trim('\u0000', '\uFFFD'))
                    if (result[i].toAddress != null) map.putString("toAddress", result[i].toAddress)
                    nativeArray.pushMap(map)
                }

                return@wrap nativeArray
            }
        }
    }

    @ReactMethod
    fun rescan(alias: String, height: Int, promise: Promise) {
        val wallet = getWallet(alias)
        moduleScope.launch {
            wallet.rewindToNearestHeight(BlockHeight.new(wallet.network, height.toLong()))
        }
    }

    @ReactMethod
    fun deriveViewingKey(seedBytesHex: String, network: String = "mainnet", promise: Promise) {
        var keys = runBlocking { DerivationTool.deriveUnifiedViewingKeys(seedBytesHex.fromHex(), networks.getOrDefault(network, ZcashNetwork.Mainnet))[0] }
        val map = Arguments.createMap()
        map.putString("extfvk", keys.extfvk)
        map.putString("extpub", keys.extpub)
        promise.resolve(map)
    }

    @ReactMethod
    fun deriveSpendingKey(seedBytesHex: String, network: String = "mainnet", promise: Promise) = promise.wrap {
        return@wrap runBlocking { DerivationTool.deriveSpendingKeys(seedBytesHex.fromHex(), networks.getOrDefault(network, ZcashNetwork.Mainnet))[0] }
    }

    //
    // Properties
    //


    @ReactMethod
    fun getLatestNetworkHeight(alias: String, promise: Promise) = promise.wrap {
        val wallet = getWallet(alias)
        return@wrap wallet.latestHeight
    }

    @ReactMethod
    fun getBirthdayHeight(host: String, port: Int, promise: Promise) = promise.wrap {
        var endpoint = LightWalletEndpoint(host, port, true)
        var lightwalletService = LightWalletGrpcService.new(reactApplicationContext, endpoint)
        val height = lightwalletService.getLatestBlockHeight()
        lightwalletService.shutdown()
        return@wrap height
    }

    @ReactMethod
    fun getShieldedBalance(alias: String, promise: Promise) = promise.wrap {
        val wallet = getWallet(alias)
        val map = Arguments.createMap()
        map.putString("totalZatoshi", wallet.saplingBalances.value?.total?.toString())
        map.putString("availableZatoshi", wallet.saplingBalances.value?.available?.toString())
        return@wrap map
    }

    @ReactMethod
    fun spendToAddress(
        alias: String,
        zatoshi: String,
        toAddress: String,
        memo: String,
        fromAccountIndex: Int,
        spendingKey: String,
        promise: Promise
    ) {
        val wallet = getWallet(alias)
        wallet.coroutineScope.launch {
            try {
                wallet.sendToAddress(
                    spendingKey,
                    Zatoshi(zatoshi.toLong()),
                    toAddress,
                    memo,
                    fromAccountIndex
                ).collectWith(wallet.coroutineScope) {tx ->
                    // this block is called repeatedly for each update to the pending transaction, including all 10 confirmations
                    // the promise either shouldn't be used (and rely on events instead) or it can be resolved once the transaction is submitted to the network or mined
                    if (tx.isSubmitSuccess()) { // alternatively use it.isMined() but be careful about making a promise that never resolves!
                        val map = Arguments.createMap()
                        map.putString("txId", tx.rawTransactionId?.toHexReversed())
                        map.putString("raw", tx.raw?.toHexReversed())
                        promise.resolve(map)
                    } else if (tx.isFailure()) {
                        val map = Arguments.createMap()
                        if (tx.errorMessage != null) map.putString("errorMessage", tx.errorMessage)
                        if (tx.errorCode != null) map.putString("errorCode", tx.errorCode.toString())
                        promise.resolve(map)
                    }
                }
            } catch (t: Throwable) {
                promise.reject("Err", t)
            }
        }

    }

    //
    // AddressTool
    //

    @ReactMethod
    fun deriveShieldedAddress(viewingKey: String, network: String = "mainnet", promise: Promise) = promise.wrap {
        return@wrap runBlocking { DerivationTool.deriveShieldedAddress(viewingKey, networks.getOrDefault(network, ZcashNetwork.Mainnet)) }
    }

    @ReactMethod
    fun isValidShieldedAddress(address: String, network: String, promise: Promise) {
        moduleScope.launch {
            promise.wrap {
                var isValid = false
                val wallets = synchronizerMap.asIterable()
            for (wallet in wallets) {
                if (wallet.value.network.networkName == network) {
                  isValid = wallet.value.isValidShieldedAddr(address)
                  break
                }
              }
              return@wrap isValid
            }
        }
    }

    @ReactMethod
    fun isValidTransparentAddress(address: String, network: String, promise: Promise) {
        moduleScope.launch {
            promise.wrap {
              var isValid = false
              val wallets = synchronizerMap.asIterable()
              for (wallet in wallets) {
                if (wallet.value.network.networkName == network) {
                  isValid = wallet.value.isValidTransparentAddr(address)
                  break
                }
              }
              return@wrap isValid
            }
        }
    }

    //
    // Utilities
    //

    /**
     * Retrieve wallet object from synchronizer map
     */
    private fun getWallet(alias: String): SdkSynchronizer {
        val wallet = synchronizerMap.get(alias)
        if (wallet == null) throw Exception("Wallet not found")
        return wallet
    }


    /**
     * Wrap the given block of logic in a promise, rejecting for any error.
     */
    private inline fun <T> Promise.wrap(block: () -> T) {
        try {
            resolve(block())
        } catch (t: Throwable) {
            reject("Err", t)
        }
    }

    private fun sendEvent(eventName: String, putArgs: (WritableMap) -> Unit) {
        val args = Arguments.createMap()
        putArgs(args)
        reactApplicationContext
            .getJSModule(RCTDeviceEventEmitter::class.java)
            .emit(eventName, args)
    }

    inline fun ByteArray.toHexReversed(): String {
      val sb = StringBuilder(size * 2)
      var i = size - 1
      while (i >= 0)
        sb.append(String.format("%02x", this[i--]))
      return sb.toString()
    }
}
