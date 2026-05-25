package org.opengb.espi

/**
 * XML namespaces used by NAESB ESPI (Green Button) feeds.
 *
 * The Atom envelope is unprefixed (default namespace). Usage payloads (UsagePoint,
 * MeterReading, IntervalBlock, ReadingType, LocalTimeParameters) live under the `espi`
 * namespace; customer payloads (Customer, CustomerAccount, CustomerAgreement,
 * ServiceLocation, EndDevice, Meter) live under the `espi/customer` namespace.
 *
 * Real feeds from Burlington Hydro emit different combinations of these depending on the
 * download type — the parser handles a single feed type at a time and dispatches content
 * by qualified element name.
 */
object EspiNamespaces {
  const val ATOM: String = "http://www.w3.org/2005/Atom"
  const val ESPI: String = "http://naesb.org/espi"
  const val ESPI_CUSTOMER: String = "http://naesb.org/espi/customer"
}
