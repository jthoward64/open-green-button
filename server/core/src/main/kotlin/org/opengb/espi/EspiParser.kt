package org.opengb.espi

import nl.adaptivity.xmlutil.serialization.XML

/**
 * Parser for NAESB ESPI (Green Button) Atom feeds.
 *
 * Two flavours are supported by the same entry point:
 *  - Usage feeds: UsagePoint + MeterReading + IntervalBlock + ReadingType + LocalTimeParameters.
 *  - Customer feeds: Customer + CustomerAccount + CustomerAgreement + ServiceLocation + EndDevice + Meter.
 *
 * Polymorphic dispatch happens by qualified element name (xmlutil `autoPolymorphic = true`),
 * so the same `EspiAtomEntry.content.body` field is populated with whichever `EspiPayload`
 * subtype matches. Unknown elements are ignored — utility feeds carry vendor extensions and
 * we'd rather a future utility's quirk not blow up parsing.
 */
object EspiParser {
  private val xml: XML =
    XML {
      defaultPolicy {
        autoPolymorphic = true
        // Tolerate unknown elements/attributes — utility feeds carry vendor extensions and
        // ESPI minor revisions that aren't worth a parser failure.
        unknownChildHandler =
          nl.adaptivity.xmlutil.serialization.UnknownChildHandler { _, _, _, _, _ -> emptyList() }
      }
    }

  fun parseFeed(source: String): EspiAtomFeed = xml.decodeFromString(EspiAtomFeed.serializer(), source)
}
