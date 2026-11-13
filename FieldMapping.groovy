import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import groovy.xml.MarkupBuilder

import java.io.StringWriter
import java.math.RoundingMode


def Message processData(Message message) {

    /*
     * Read the validated JSON payload using streaming.
     */
    java.io.Reader reader =
        message.getBody(java.io.Reader.class)

    def json =
        new JsonSlurper().parse(reader)

    def documentHeader =
        json.documentHeader

    def vendor =
        json.vendor

    List expenseList =
        normalizeToList(
            json.expenses?.expense
        )

    List currencyAmountList =
        normalizeToList(
            json.currencyAmounts?.currencyAmount
        )


    StringWriter writer =
        new StringWriter()

    MarkupBuilder xml =
        new MarkupBuilder(writer)

    xml.mkp.xmlDeclaration(
        version: "1.0",
        encoding: "UTF-8"
    )


    xml.BAPI_ACC_DOCUMENT_POST(
        "xmlns":
            "urn:sap-com:document:sap:rfc:functions"
    ) {

        /*
         * ========================================================
         * DOCUMENT HEADER
         * ========================================================
         */
        DOCUMENTHEADER {
            OBJ_TYPE(
                documentHeader?.objType ?: ""
            )

            USERNAME(
                documentHeader?.username ?: ""
            )

            COMP_CODE(
                documentHeader?.companyCode ?: ""
            )

            DOC_DATE(
                documentHeader?.documentDate ?: ""
            )

            PSTNG_DATE(
                documentHeader?.postingDate ?: ""
            )

            FISC_YEAR(
                documentHeader?.fiscalYear ?: ""
            )

            FIS_PERIOD(
                documentHeader?.fiscalPeriod ?: ""
            )

            DOC_TYPE(
                documentHeader?.documentType ?: ""
            )

            REF_DOC_NO(
                documentHeader?.referenceDocumentNumber ?: ""
            )
        }


        /*
         * ========================================================
         * G/L ACCOUNT ITEMS
         * ========================================================
         *
         * Amount and itemNoTax are no longer read from an expense.
         */
        ACCOUNTGL {
            expenseList.each { expense ->
                item {
                    ITEMNO_ACC(
                        expense?.itemNumber ?: ""
                    )

                    GL_ACCOUNT(
                        expense?.glAccount ?: ""
                    )

                    ITEM_TEXT(
                        expense?.itemText ?: ""
                    )

                    /*
                     * Use the expense company code when provided.
                     * Otherwise, use the header company code.
                     */
                    COMP_CODE(
                        expense?.companyCode ?:
                            documentHeader?.companyCode ?:
                            ""
                    )

                    ALLOC_NMBR(
                        expense?.assignment ?: ""
                    )

                    COSTCENTER(
                        expense?.costCenter ?: ""
                    )
                }
            }
        }


        /*
         * ========================================================
         * VENDOR ITEM
         * ========================================================
         */
        ACCOUNTPAYABLE {
            if (vendor != null) {
                item {
                    ITEMNO_ACC(
                        vendor?.itemNumber ?: ""
                    )

                    VENDOR_NO(
                        vendor?.vendorNumber ?: ""
                    )

                    ALLOC_NMBR(
                        vendor?.assignment ?: ""
                    )

                    BUSINESSPLACE(
                        vendor?.businessPlace ?: ""
                    )

                    SECTIONCODE(
                        vendor?.sectionCode ?: ""
                    )
                }
            }
        }


        /*
         * ========================================================
         * CURRENCY AMOUNTS
         * ========================================================
         *
         * Amounts are mapped directly from:
         * currencyAmounts.currencyAmount
         *
         * No vendor balancing amount is calculated here.
         */
        CURRENCYAMOUNT {
            currencyAmountList.each { currencyAmount ->
                item {
                    ITEMNO_ACC(
                        currencyAmount?.itemNumber ?: ""
                    )

                    CURRENCY(
                        currencyAmount?.currency ?:
                            documentHeader?.currency ?:
                            ""
                    )

                    AMT_DOCCUR(
                        currencyAmount?.amount != null
                            ? formatAmount(
                                toBigDecimal(
                                    currencyAmount.amount
                                )
                            )
                            : ""
                    )

                    /*
                     * Optional AMT_BASE support.
                     * The element is generated only when the incoming
                     * currency row contains taxBaseAmount.
                     */
                    if (currencyAmount?.taxBaseAmount != null) {
                        AMT_BASE(
                            formatAmount(
                                toBigDecimal(
                                    currencyAmount.taxBaseAmount
                                )
                            )
                        )
                    }
                }
            }
        }


        /*
         * ========================================================
         * INVOICE TRACKING EXTENSION
         * ========================================================
         */
        EXTENSION2 {
            item {
                STRUCTURE(
                    "ZFITBILLTRACK"
                )

                VALUEPART1(
                    documentHeader?.invoiceTrackingNumber ?: ""
                )

                VALUEPART2("")
                VALUEPART3("")
                VALUEPART4("")
            }
        }
    }


    message.setBody(
        writer.toString()
    )

    message.setHeader(
        "Content-Type",
        "application/xml"
    )

    return message
}


/*
 * Accept either a JSON array or a single JSON object.
 */
private static List normalizeToList(def node) {

    if (node == null) {
        return []
    }

    if (node instanceof Collection) {
        return new ArrayList(node)
    }

    return [node]
}


/*
 * Convert an incoming JSON number or numeric String
 * to BigDecimal.
 */
private static BigDecimal toBigDecimal(def value) {

    if (
        value == null ||
        value.toString().trim().isEmpty()
    ) {
        return BigDecimal.ZERO
    }

    return new BigDecimal(
        value.toString().trim()
    )
}


/*
 * Format BAPI currency values with two decimal places.
 */
private static String formatAmount(
    BigDecimal value
) {
    return value
        .setScale(
            2,
            RoundingMode.HALF_UP
        )
        .toPlainString()
}

