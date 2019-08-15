package com.r3.businessnetworks.ledgersync

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import net.corda.core.contracts.ContractState
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.identity.Party
import net.corda.core.node.AppServiceHub
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.Vault.StateStatus.ALL
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.DEFAULT_PAGE_SIZE
import net.corda.core.node.services.vault.PageSpecification
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.QueryCriteria.VaultQueryCriteria
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.utilities.loggerFor
import sun.security.util.ByteArrayLexOrder
import java.io.File
import java.nio.file.Paths

/**
 * Provides a list of transaction hashes referring to transactions in which all of the given parties are participating.
 * NOTE: VaultQueryCriteria.withParticipants(list) would filter out the states which have ANY party in the list as participant
 * This will load FILTERED STATES into memory page by page. The page size will be either defined in config file or using DEFAULT_PAGE_SIZE.
 *
 */

fun ServiceHub.withParticipants(vararg parties: Party, pageSize: Int = this.cordaService(ConfigurationService::class.java).pageSize()): List<SecureHash> {
    val list = mutableListOf<SecureHash>()
    var criteria: QueryCriteria = VaultQueryCriteria(status = ALL)
    parties.toList().map {
        val newCriteria = VaultQueryCriteria(status = ALL).withParticipants(listOf(it))
        criteria = criteria.and(newCriteria)
    }

    var count = 1
    var page = vaultService.queryBy<ContractState>(criteria, PageSpecification(count, pageSize))
    while(page.states.isNotEmpty()) {
        page.states.filter {
            it.state.data.participants.containsAll(parties.toList())
        }.map {
            list.add(it.ref.txhash)
        }
        count ++
        page = vaultService.queryBy(criteria, PageSpecification(count, pageSize))
    }
    return list
}


/**
 * Calculates a compound hash of multiple hashes by hashing their concatenation in lexical order.
 */
fun List<SecureHash>.hash(): SecureHash = map {
    it.bytes
}.sortedWith(ByteArrayLexOrder()).fold(ByteArray(0)) { acc, hash ->
    acc + hash
}.sha256()

/*
    read pageSize from ledgersync.conf. More config could be added if needed.
 */
@CordaService
class ConfigurationService(appServiceHub : AppServiceHub): SingletonSerializeAsToken()  {
    companion object {
        private val logger = loggerFor<ConfigurationService>()
    }
    private val configName = "ledgersync"
    private var _config = loadConfig()
    private fun loadConfig() : Config? {
        val fileName = "$configName.conf"
        val defaultLocation = (Paths.get("cordapps").resolve("config").resolve(fileName)).toFile()
        var loc: File? = null
        loc = if(defaultLocation.exists())
            defaultLocation
        else {
            val configResource = this::class.java.classLoader.getResource(fileName)
            configResource?.let { File(configResource.toURI()) }
        }
        return if(loc == null) {
            logger.warn("Cannot find $configName.conf")
            null
        }
        else try {
            ConfigFactory.parseFile(loc)
        }catch (e: Exception){
            logger.warn("Failed to parse ${loc.absolutePath} due to ${e.message} ")
            null
        }
    }
    open fun pageSize() = _config?.let{
        var size = DEFAULT_PAGE_SIZE
        try {
            size = it.getInt("pageSize")
            logger.info("pageSize = ${it.getInt("pageSize")}")
        } catch (e: Exception) {
            logger.warn("pageSize is not properly configured! Exception: ${e.message} \n" +
                    "Using DEFAULT_PAGE_SIZE = $DEFAULT_PAGE_SIZE")
        }
        size
    } ?: run {
        logger.warn("Using DEFAULT_PAGE_SIZE = $DEFAULT_PAGE_SIZE")
        DEFAULT_PAGE_SIZE
    }
}