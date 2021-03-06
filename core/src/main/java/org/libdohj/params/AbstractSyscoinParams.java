/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.libdohj.params;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;


import com.google.common.base.Stopwatch;
import org.bitcoinj.core.*;

import static com.google.common.base.Preconditions.checkState;
import static org.bitcoinj.core.Coin.COIN;

import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptOpCodes;

import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.utils.MonetaryFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.libdohj.core.AltcoinSerializer;
import org.libdohj.core.AuxPoWNetworkParameters;

/**
 * Common parameters for Syscoin networks.
 */
public abstract class AbstractSyscoinParams extends NetworkParameters implements AuxPoWNetworkParameters {
    /** Standard format for the SYSCOIN denomination. */
    public static final MonetaryFormat SYSCOIN;
    /** Standard format for the mSYSCOIN denomination. */
    public static final MonetaryFormat MSYSCOIN;
    /** Standard format for the Koinu denomination. */
    public static final MonetaryFormat KOINU;

    public static final int AUXPOW_CHAIN_ID = 0x1000;


    /** Currency code for base 1 Syscoin. */
    public static final String CODE_SYSCOIN = "SYSCOIN";
    /** Currency code for base 1/1,000 Syscoin. */
    public static final String CODE_MSYSCOIN = "mSYSCOIN";
    /** Currency code for base 1/100,000,000 Syscoin. */
    public static final String CODE_KOINU = "Koinu";


    static {
        SYSCOIN = MonetaryFormat.BTC.noCode()
            .code(0, CODE_SYSCOIN)
            .code(3, CODE_MSYSCOIN)
            .code(7, CODE_KOINU);
        MSYSCOIN = SYSCOIN.shift(3).minDecimals(2).optionalDecimals(2);
        KOINU = SYSCOIN.shift(7).minDecimals(0).optionalDecimals(2);
    }

    /** The string returned by getId() for the main, production network where people trade things. */
    public static final String ID_SYSCOIN_MAINNET = "org.syscoin.production";
    /** The string returned by getId() for the testnet. */
    public static final String ID_SYSCOIN_TESTNET = "org.syscoin.test";
    public static final String ID_SYSCOIN_REGTEST = "org.syscoin.regtest";

    public static final int SYSCOIN_TARGET_TIMESPAN = 6 * 60 * 60; // 6h retarget
    public static final int SYSCOIN_TARGET_SPACING = 1 * 60;  // 1 minute per block.
    public static final int SYSCOIN_INTERVAL = SYSCOIN_TARGET_TIMESPAN / SYSCOIN_TARGET_SPACING;
    private static final int BLOCK_VERSION_FLAG_AUXPOW = 0x00000100;
    protected Logger log = LoggerFactory.getLogger(AbstractSyscoinParams.class);

    private static final Coin BASE_SUBSIDY   = COIN.multiply(500000);
    private static final Coin STABLE_SUBSIDY = COIN.multiply(10000);

    public AbstractSyscoinParams() {
        super();
        interval = SYSCOIN_INTERVAL;
        targetTimespan = SYSCOIN_TARGET_TIMESPAN;
    }

    @Override
    public Coin getBlockSubsidy(final int height) {

        return STABLE_SUBSIDY;

    }
    /**
     * Checks if we are at a difficulty transition point.
     * @param height The height of the previous stored block
     * @return If this is a difficulty transition point
     */
    public final boolean isDifficultyTransitionPoint(final int height) {
        return ((height + 1) % this.getInterval()) == 0;
    }

    @Override
    public void checkDifficultyTransitions(final StoredBlock storedPrev, final Block nextBlock,
                                           final BlockStore blockStore) throws VerificationException, BlockStoreException {
        final Block prev = storedPrev.getHeader();

        // Is this supposed to be a difficulty transition point?
        if (!isDifficultyTransitionPoint(storedPrev.getHeight())) {

            // No ... so check the difficulty didn't actually change.
            if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                        ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                        Long.toHexString(prev.getDifficultyTarget()));
            return;
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
        // two weeks after the initial block chain download.
        final Stopwatch watch = Stopwatch.createStarted();
        Sha256Hash hash = prev.getHash();
        StoredBlock cursor = null;
        final int interval = this.getInterval();
        for (int i = 0; i < interval; i++) {
            cursor = blockStore.get(hash);
            if (cursor == null) {
                // This should never happen. If it does, it means we are following an incorrect or busted chain.
                throw new VerificationException(
                        "Difficulty transition point but we did not find a way back to the last transition point. Not found: " + hash);
            }
            hash = cursor.getHeader().getPrevBlockHash();
        }
        checkState(cursor != null && isDifficultyTransitionPoint(cursor.getHeight() - 1),
                "Didn't arrive at a transition point.");
        watch.stop();
        if (watch.elapsed(TimeUnit.MILLISECONDS) > 50)
            log.info("Difficulty transition traversal took {}", watch);

        Block blockIntervalAgo = cursor.getHeader();
        int timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
        // Limit the adjustment step.
        final int targetTimespan = this.getTargetTimespan();
        if (timespan < targetTimespan / 4)
            timespan = targetTimespan / 4;
        if (timespan > targetTimespan * 4)
            timespan = targetTimespan * 4;

        BigInteger newTarget = Utils.decodeCompactBits(prev.getDifficultyTarget());
        newTarget = newTarget.multiply(BigInteger.valueOf(timespan));
        newTarget = newTarget.divide(BigInteger.valueOf(targetTimespan));

        if (newTarget.compareTo(this.getMaxTarget()) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newTarget.toString(16));
            newTarget = this.getMaxTarget();
        }

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        long receivedTargetCompact = nextBlock.getDifficultyTarget();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newTarget = newTarget.and(mask);
        long newTargetCompact = Utils.encodeCompactBits(newTarget);

        if (newTargetCompact != receivedTargetCompact)
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                    Long.toHexString(newTargetCompact) + " vs " + Long.toHexString(receivedTargetCompact));
    }


    public MonetaryFormat getMonetaryFormat() {
        return SYSCOIN;
    }

    @Override
    public Coin getMaxMoney() {
        // TODO: Change to be Syscoin compatible
        return MAX_MONEY;
    }

    @Override
    public Coin getMinNonDustOutput() {
        return Coin.COIN;
    }

    @Override
    public String getUriScheme() {
        return "syscoin";
    }

    @Override
    public boolean hasMaxMoney() {
        return false;
    }





    @Override
    public int getChainID() {
        return AUXPOW_CHAIN_ID;
    }

    /**
     * Whether this network has special rules to enable minimum difficulty blocks
     * after a long interval between two blocks (i.e. testnet).
     */
    public abstract boolean allowMinDifficultyBlocks();

    /**
     * Get the hash to use for a block.
     */
    @Override
    public Sha256Hash getBlockDifficultyHash(Block block) {
        return ((AltcoinBlock) block).getHash();
    }

    @Override
    public AltcoinSerializer getSerializer(boolean parseRetain) {
        return new AltcoinSerializer(this, parseRetain);
    }

    @Override
    public int getProtocolVersionNum(final ProtocolVersion version) {
        return version.getBitcoinProtocolVersion();
    }

    @Override
    public boolean isAuxPoWBlockVersion(long version) {
        return (version & BLOCK_VERSION_FLAG_AUXPOW) > 0;
    }
       


    private static class CheckpointEncounteredException extends Exception {

        private CheckpointEncounteredException() {
        }
    }
}
