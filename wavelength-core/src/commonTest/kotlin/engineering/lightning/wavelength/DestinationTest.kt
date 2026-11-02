package engineering.lightning.wavelength

import kotlin.test.Test
import kotlin.test.assertEquals

// Vectors ported from the TypeScript SDK's destination.test.ts.
class DestinationTest {
    private fun known(sat: Long) = Destination.Invoice(InvoiceAmount.Known(sat))
    private val amountless = Destination.Invoice(InvoiceAmount.Amountless)
    private val unrepresentable = Destination.Invoice(InvoiceAmount.Unrepresentable)

    @Test
    fun empty() {
        listOf("", "   ", "\n").forEach { assertEquals(Destination.Empty, Destination.classify(it)) }
    }

    @Test
    fun readsTheAmountFromTheHrp() {
        assertEquals(known(250_000), Destination.classify("lnbc2500u1pvjluez"))
        assertEquals(known(2_000_000), Destination.classify("lnbc20m1pvjluez"))
        assertEquals(known(50_000), Destination.classify("lnbc500u1p3xnhl2"))
        assertEquals(known(10), Destination.classify("lntbs100n1pvjl"))
        assertEquals(known(100), Destination.classify("lnbc1000n1pvjluez"))
    }

    @Test
    fun amountlessVersusWholeBitcoin() {
        assertEquals(amountless, Destination.classify("lnbc1pvjluezpp5"))
        assertEquals(known(100_000_000), Destination.classify("lnbc11pvjluezpp5"))
        assertEquals(amountless, Destination.classify("lnbc1pvjluez"))
    }

    @Test
    fun everyNetworkPrefix() {
        assertEquals(known(50_000), Destination.classify("lnbcrt500u1pvjl"))
        assertEquals(amountless, Destination.classify("lnbcrt1pvjl"))
        assertEquals(known(2_000_000), Destination.classify("lntb20m1pvjluez"))
    }

    @Test
    fun addresses() {
        listOf("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7k3j9v7", "bcrt1qw508d6", "tark1qqp6f9r", "garbage").forEach {
            assertEquals(Destination.Address, Destination.classify(it))
        }
    }

    @Test
    fun exactNanoAndPico() {
        assertEquals(known(3), Destination.classify("lnbc30n1pvjluez"))
        assertEquals(known(3), Destination.classify("lnbc30000p1pvjl"))
    }

    @Test
    fun subSatoshiOrUnsafe() {
        assertEquals(unrepresentable, Destination.classify("lnbc1p1pvjl"))
        assertEquals(unrepresentable, Destination.classify("lnbc9n1pvjl"))
        assertEquals(unrepresentable, Destination.classify("lnbc99999999999999999999m1pvjl"))
    }

    @Test
    fun caseInsensitive() {
        assertEquals(known(250_000), Destination.classify("LNBC2500U1PVJLUEZ"))
    }
}
