import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.util.XmlSlurper


def Message processData(Message message) {

    def responseXml

    /*
     * Parse the RFC XML response using streaming.
     */
    try {
        java.io.Reader reader =
            message.getBody(java.io.Reader.class)

        XmlSlurper parser =
            new XmlSlurper(false, false)

        /*
         * Prevent external XML entities where supported.
         */
        try {
            parser.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
            )

            parser.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false
            )

            parser.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false
            )
        } catch (Exception ignored) {
            /*
             * Some CPI runtimes do not allow changing these features.
             */
        }

        responseXml =
            parser.parse(reader)

    } catch (Exception exception) {

        List parsingErrors = [[
            errorCode : "INVALID_SAP_RESPONSE",
            field     : "sapResponse",
            message   : "SAP returned an invalid or unreadable XML response.",
            sapMessage: exception.message ?: "XML parsing failed."
        ]]

        return buildFailureResponse(
            message,
            parsingErrors,
            "",
            "",
            ""
        )
    }


    String objectKey =
        getDirectChildText(
            responseXml,
            "OBJ_KEY"
        )

    String objectSystem =
        getDirectChildText(
            responseXml,
            "OBJ_SYS"
        )

    String objectType =
        getDirectChildText(
            responseXml,
            "OBJ_TYPE"
        )


    /*
     * Extract only RETURN/item records.
     *
     * This avoids reading ACCOUNTGL/item, ACCOUNTPAYABLE/item,
     * CURRENCYAMOUNT/item and other BAPI response structures.
     */
    List returnItems =
        extractReturnItems(responseXml)


    List errors = []
    List warnings = []
    List informationMessages = []


    returnItems.each { returnItem ->

        String type =
            getDirectChildText(
                returnItem,
                "TYPE"
            ).toUpperCase()

        String sapId =
            getDirectChildText(
                returnItem,
                "ID"
            )

        String sapNumber =
            getDirectChildText(
                returnItem,
                "NUMBER"
            )

        String sapMessage =
            getDirectChildText(
                returnItem,
                "MESSAGE"
            )

        String parameter =
            getDirectChildText(
                returnItem,
                "PARAMETER"
            )

        String row =
            getDirectChildText(
                returnItem,
                "ROW"
            )

        String sapField =
            getDirectChildText(
                returnItem,
                "FIELD"
            )

        String system =
            getDirectChildText(
                returnItem,
                "SYSTEM"
            )


        Map originalSapDetails = [
            sapType    : type,
            sapId      : sapId,
            sapNumber  : sapNumber,
            sapMessage : sapMessage,
            parameter  : parameter,
            row        : row,
            sapField   : sapField,
            system     : system
        ]


        /*
         * E = Error
         * A = Abort
         * X = System exit/termination
         */
        if (type in ["E", "A", "X"]) {

            Map classifiedError =
                classifySapError(
                    sapId,
                    sapNumber,
                    sapMessage,
                    parameter,
                    sapField
                )

            classifiedError.putAll(
                originalSapDetails
            )

            errors << classifiedError

        } else if (type == "W") {

            warnings << originalSapDetails

        } else if (type in ["S", "I"]) {

            informationMessages << originalSapDetails
        }
    }


    /*
     * SAP may occasionally return no E/A/X message but still fail to
     * generate a document key. Treat such a response as unsuccessful.
     */
    boolean validObjectKey =
        objectKey != null &&
        !objectKey.trim().isEmpty() &&
        objectKey.trim() != "\$"


    if (
        errors.isEmpty() &&
        !validObjectKey
    ) {
        errors << [
            errorCode : "SAP_DOCUMENT_NOT_POSTED",
            field     : "sapResponse.OBJ_KEY",
            message   : "SAP did not return a valid accounting document number.",
            sapType   : "E",
            sapId     : "",
            sapNumber : "",
            sapMessage: "OBJ_KEY is empty or contains only \$.",
            parameter : "OBJ_KEY",
            row       : "0",
            sapField  : "OBJ_KEY",
            system    : objectSystem
        ]
    }


    /*
     * ============================================================
     * FAILURE RESPONSE
     * ============================================================
     */

    if (!errors.isEmpty()) {

        sortErrorsByPriority(errors)

        return buildFailureResponse(
            message,
            errors,
            objectKey,
            objectSystem,
            objectType
        )
    }


    /*
     * ============================================================
     * SUCCESS RESPONSE
     * ============================================================
     */

    Map successResponse = [
        status        : "SUCCESS",
        message       : "Accounting document posted successfully.",
        documentNumber: extractDocumentNumber(objectKey),
        objectKey     : objectKey,
        objectType    : objectType,
        system        : objectSystem
    ]


    if (!warnings.isEmpty()) {
        successResponse.warnings = warnings
    }

    if (!informationMessages.isEmpty()) {
        successResponse.sapMessages =
            informationMessages
    }


    message.setBody(
        JsonOutput.prettyPrint(
            JsonOutput.toJson(successResponse)
        )
    )

    message.setHeader(
        "Content-Type",
        "application/json"
    )

    message.setHeader(
        "CamelHttpResponseCode",
        200
    )

    message.setProperty(
        "sapPostingSuccessful",
        "true"
    )

    message.setProperty(
        "sapDocumentKey",
        objectKey
    )

    message.setProperty(
        "sapDocumentNumber",
        extractDocumentNumber(objectKey)
    )

    message.setProperty(
        "sapObjectSystem",
        objectSystem
    )

    return message
}


/*
 * Extract RETURN/item records independently of XML namespaces.
 */
private static List extractReturnItems(
    def responseXml
) {

    List returnItems = []

    responseXml.depthFirst().each { node ->

        if (getLocalName(node.name()) == "RETURN") {

            node.children().each { child ->

                if (getLocalName(child.name()) == "item") {
                    returnItems << child
                }
            }
        }
    }

    return returnItems
}


/*
 * Read a direct child value independently of namespace prefixes.
 */
private static String getDirectChildText(
    def parentNode,
    String requiredName
) {

    if (parentNode == null) {
        return ""
    }

    def matchingChild =
        parentNode.children().find { child ->

            getLocalName(child.name()) ==
                requiredName
        }

    return matchingChild != null
        ? matchingChild.text()?.trim() ?: ""
        : ""
}


/*
 * Remove an XML namespace prefix from a node name.
 */
private static String getLocalName(
    def nodeName
) {

    if (nodeName == null) {
        return ""
    }

    String completeName =
        nodeName.toString()

    if (completeName.contains("}")) {
        return completeName.substring(
            completeName.lastIndexOf("}") + 1
        )
    }

    if (completeName.contains(":")) {
        return completeName.substring(
            completeName.lastIndexOf(":") + 1
        )
    }

    return completeName
}


/*
 * Classify an SAP error into the required response structure.
 *
 * SAP message ID and number are retained in the response.
 * Message-text matching is used because exact SAP message numbers can
 * differ depending on configuration, localization and custom exits.
 */
private static Map classifySapError(
    String sapId,
    String sapNumber,
    String sapMessage,
    String parameter,
    String sapField
) {

    String messageText =
        sapMessage?.trim() ?: ""

    String normalizedMessage =
        messageText.toLowerCase()

    String normalizedParameter =
        parameter?.toLowerCase() ?: ""

    String normalizedField =
        sapField?.toLowerCase() ?: ""

    String searchableText =
        "${normalizedMessage} " +
        "${normalizedParameter} " +
        "${normalizedField}"


    /*
     * 1. Company Code
     */
    if (
        containsAny(
            searchableText,
            [
                "company code",
                "comp_code",
                "bukrs"
            ]
        )
    ) {
        return [
            errorCode : "COMPANY_CODE",
            field     : "documentHeader.companyCode",
            message   : "Company Code is mandatory/invalid."
        ]
    }


    /*
     * 2. Accounting document date
     */
    if (
        containsAny(
            searchableText,
            [
                "accounting document date",
                "document date",
                "doc_date",
                "bldat"
            ]
        )
    ) {
        return [
            errorCode : "DOCUMENT_DATE",
            field     : "documentHeader.documentDate",
            message   : "Accounting document date is invalid."
        ]
    }


    /*
     * 29. Closed posting period must be identified before the generic
     * posting-date check.
     */
    if (
        containsAny(
            searchableText,
            [
                "posting period is closed",
                "period is closed",
                "period not open",
                "posting period",
                "not allowed in period",
                "not open for account type",
                "cannot be posted in period"
            ]
        ) &&
        containsAny(
            searchableText,
            [
                "closed",
                "not open",
                "not allowed",
                "cannot be posted"
            ]
        )
    ) {
        return [
            errorCode : "PERIOD_CLOSED",
            field     : "documentHeader.postingDate",
            message   : "Document cannot be posted in current period."
        ]
    }


    /*
     * 3. Posting date
     */
    if (
        containsAny(
            searchableText,
            [
                "posting date",
                "pstng_date",
                "budat"
            ]
        )
    ) {
        return [
            errorCode : "POSTING_DATE",
            field     : "documentHeader.postingDate",
            message   : "Posting date is invalid."
        ]
    }


    /*
     * 4. Fiscal year/period
     */
    if (
        containsAny(
            searchableText,
            [
                "fiscal year",
                "fiscal period",
                "fis_period",
                "fisc_year",
                "gjahr",
                "monat"
            ]
        )
    ) {
        return [
            errorCode : "FISCAL_YEAR_PERIOD",
            field     : "documentHeader.fiscalYear",
            message   : "Fiscal year/period is invalid."
        ]
    }


    /*
     * 5. Document type
     */
    if (
        containsAny(
            searchableText,
            [
                "document type",
                "doc_type",
                "blart"
            ]
        )
    ) {
        return [
            errorCode : "DOCUMENT_TYPE",
            field     : "documentHeader.documentType",
            message   : "Document type KR is not valid."
        ]
    }


    /*
     * 7. Vendor not extended to company code.
     * This must be checked before the generic vendor-not-found condition.
     */
    if (
        containsAny(
            searchableText,
            [
                "vendor is not extended",
                "vendor not extended",
                "not created in company code",
                "not defined in company code",
                "not maintained for company code",
                "company code data is missing",
                "supplier is not extended",
                "supplier not extended"
            ]
        )
    ) {
        return [
            errorCode : "VENDOR_COMPANY_CODE",
            field     : "vendor.vendorNumber",
            message   : "Vendor is not extended to company code."
        ]
    }


    /*
     * 6. Vendor does not exist
     */
    if (
        containsAny(
            searchableText,
            [
                "vendor does not exist",
                "vendor not found",
                "vendor is not defined",
                "supplier does not exist",
                "supplier not found",
                "vendor_no",
                "lifnr"
            ]
        )
    ) {
        return [
            errorCode : "VENDOR_NOT_FOUND",
            field     : "vendor.vendorNumber",
            message   : "Vendor does not exist."
        ]
    }


    /*
     * 9. G/L account invalid for company code.
     * Check before generic G/L-account-not-found.
     */
    if (
        containsAny(
            searchableText,
            [
                "g/l account is not valid for company code",
                "g/l account not valid for company code",
                "account is not created in company code",
                "account not created in company code",
                "not defined in company code",
                "not maintained in company code"
            ]
        ) &&
        containsAny(
            searchableText,
            [
                "g/l",
                "gl account",
                "account"
            ]
        )
    ) {
        return [
            errorCode : "GL_COMPANY_CODE",
            field     : "expenses.expense.glAccount",
            message   : "G/L account is not valid for company code."
        ]
    }


    /*
     * 8. G/L account does not exist
     */
    if (
        containsAny(
            searchableText,
            [
                "g/l account does not exist",
                "g/l account not found",
                "gl account does not exist",
                "account does not exist",
                "gl_account",
                "hkont",
                "saknr"
            ]
        )
    ) {
        return [
            errorCode : "GL_NOT_FOUND",
            field     : "expenses.expense.glAccount",
            message   : "G/L account does not exist."
        ]
    }


    /*
     * 12. Cost center mandatory
     */
    if (
        containsAny(
            searchableText,
            [
                "cost center is mandatory",
                "cost center required",
                "cost center must be entered",
                "costcenter must be entered",
                "cost center missing"
            ]
        )
    ) {
        return [
            errorCode : "COST_CENTER_MANDATORY",
            field     : "expenses.expense.costCenter",
            message   : "Cost center is mandatory."
        ]
    }


    /*
     * 11. Cost center invalid on posting date
     */
    if (
        containsAny(
            searchableText,
            [
                "cost center is not valid on posting date",
                "cost center not valid on posting date",
                "cost center is only valid",
                "cost center validity",
                "not valid on"
            ]
        ) &&
        containsAny(
            searchableText,
            [
                "cost center",
                "costcenter",
                "kostl"
            ]
        )
    ) {
        return [
            errorCode : "COST_CENTER_DATE",
            field     : "expenses.expense.costCenter",
            message   : "Cost center is not valid on posting date."
        ]
    }


    /*
     * 10. Cost center does not exist
     */
    if (
        containsAny(
            searchableText,
            [
                "cost center does not exist",
                "cost center not found",
                "costcenter does not exist",
                "costcenter",
                "cost center",
                "kostl"
            ]
        )
    ) {
        return [
            errorCode : "COST_CENTER_NOT_FOUND",
            field     : "expenses.expense.costCenter",
            message   : "Cost center does not exist."
        ]
    }


    /*
     * 14. Tax code not allowed for G/L account
     */
    if (
        containsAny(
            searchableText,
            [
                "tax code not allowed",
                "tax code is not allowed",
                "tax category",
                "tax code cannot be used",
                "not permitted for account"
            ]
        )
    ) {
        return [
            errorCode : "TAX_CODE_GL",
            field     : "expenses.expense.taxCode",
            message   : "Tax code not allowed for G/L account."
        ]
    }


    /*
     * 13. Invalid tax code
     */
    if (
        containsAny(
            searchableText,
            [
                "tax code is invalid",
                "tax code does not exist",
                "invalid tax code",
                "tax_code",
                "mwskz"
            ]
        )
    ) {
        return [
            errorCode : "TAX_CODE_INVALID",
            field     : "expenses.expense.taxCode",
            message   : "Tax code is invalid."
        ]
    }


    /*
     * 15. Business place
     */
    if (
        containsAny(
            searchableText,
            [
                "business place",
                "businessplace",
                "bupla"
            ]
        )
    ) {
        return [
            errorCode : "BUSINESS_PLACE",
            field     : "vendor.businessPlace",
            message   : "Business Place is invalid."
        ]
    }


    /*
     * 16. Section code
     */
    if (
        containsAny(
            searchableText,
            [
                "section code",
                "sectioncode",
                "secco"
            ]
        )
    ) {
        return [
            errorCode : "SECTION_CODE",
            field     : "vendor.sectionCode",
            message   : "Section Code is invalid."
        ]
    }


    /*
     * 18. Missing currency amount.
     * Check before the generic currency condition.
     */
    if (
        containsAny(
            searchableText,
            [
                "currency amount is missing",
                "currency amount missing",
                "currencyamount not populated",
                "currency amount not transferred",
                "amount in document currency is missing",
                "amt_doccur is missing"
            ]
        )
    ) {
        return [
            errorCode : "CURRENCY_AMOUNT_MISSING",
            field     : "currencyAmounts.currencyAmount",
            message   : "Currency amount is missing."
        ]
    }


    /*
     * 17. Currency
     */
    if (
        containsAny(
            searchableText,
            [
                "currency is invalid",
                "invalid currency",
                "currency does not exist",
                "currency key",
                "currency",
                "waers"
            ]
        )
    ) {
        return [
            errorCode : "CURRENCY_INVALID",
            field     : "documentHeader.currency",
            message   : "Currency is invalid."
        ]
    }


    /*
     * 20. No accounting item information.
     * Known standard message: RW 002.
     */
    if (
        (
            sapId?.equalsIgnoreCase("RW") &&
            normalizeNumber(sapNumber) == "002"
        ) ||
        containsAny(
            searchableText,
            [
                "no item information transferred into accounting",
                "no item information transferred",
                "no accounting items transferred"
            ]
        )
    ) {
        return [
            errorCode : "NO_ITEM_INFORMATION",
            field     : "accountingItems",
            message   : "No item information transferred into Accounting."
        ]
    }


    /*
     * 21. Currency item mismatch
     */
    if (
        containsAny(
            searchableText,
            [
                "currency amount item number does not match",
                "item number does not match",
                "itemno_acc mismatch",
                "currency item mismatch",
                "no currency line exists for item"
            ]
        )
    ) {
        return [
            errorCode : "CURRENCY_ITEM_MISMATCH",
            field     : "currencyAmounts.currencyAmount.itemNumber",
            message   : "Currency amount item number does not match accounting item."
        ]
    }


    /*
     * 22. Duplicate item number
     */
    if (
        containsAny(
            searchableText,
            [
                "duplicate line item number",
                "duplicate item number",
                "item number already exists",
                "itemno_acc used more than once"
            ]
        )
    ) {
        return [
            errorCode : "DUPLICATE_ITEM_NUMBER",
            field     : "itemNumber",
            message   : "Duplicate line item number."
        ]
    }


    /*
     * 23. Vendor line missing
     */
    if (
        containsAny(
            searchableText,
            [
                "vendor line item is missing",
                "vendor item is missing",
                "accountpayable not populated",
                "no vendor line item"
            ]
        )
    ) {
        return [
            errorCode : "VENDOR_ITEM_MISSING",
            field     : "vendor",
            message   : "Vendor line item is missing."
        ]
    }


    /*
     * 24. G/L line missing
     */
    if (
        containsAny(
            searchableText,
            [
                "g/l line item is missing",
                "gl line item is missing",
                "accountgl not populated",
                "no g/l line item"
            ]
        )
    ) {
        return [
            errorCode : "GL_ITEM_MISSING",
            field     : "expenses.expense",
            message   : "G/L line item is missing."
        ]
    }


    /*
     * 25. Amount sign
     */
    if (
        containsAny(
            searchableText,
            [
                "amount sign is incorrect",
                "incorrect amount sign",
                "sign of amount",
                "debit/credit indicator",
                "debit credit amount"
            ]
        )
    ) {
        return [
            errorCode : "AMOUNT_SIGN",
            field     : "currencyAmounts.currencyAmount.amount",
            message   : "Amount sign is incorrect."
        ]
    }


    /*
     * 26. Amount does not balance
     */
    if (
        containsAny(
            searchableText,
            [
                "amount does not balance",
                "amounts do not balance",
                "currency amounts do not total zero"
            ]
        )
    ) {
        return [
            errorCode : "AMOUNT_NOT_BALANCED",
            field     : "currencyAmounts.currencyAmount",
            message   : "Amount does not balance."
        ]
    }


    /*
     * 19. General document balancing error.
     * This is intentionally checked after the more-specific amount error.
     */
    if (
        containsAny(
            searchableText,
            [
                "document is not balanced",
                "document not balanced",
                "balance in transaction currency",
                "debits and credits do not balance",
                "balance is not zero"
            ]
        )
    ) {
        return [
            errorCode : "DOCUMENT_NOT_BALANCED",
            field     : "currencyAmounts.currencyAmount",
            message   : "Document is not balanced."
        ]
    }


    /*
     * 27. Duplicate reference document
     */
    if (
        containsAny(
            searchableText,
            [
                "reference document number already exists",
                "reference document already exists",
                "reference number already exists",
                "duplicate reference document",
                "duplicate xblnr"
            ]
        )
    ) {
        return [
            errorCode : "REFERENCE_DUPLICATE",
            field     : "documentHeader.referenceDocumentNumber",
            message   : "Reference document number already exists."
        ]
    }


    /*
     * 28. Duplicate invoice tracking number
     */
    if (
        containsAny(
            searchableText,
            [
                "invoice tracking number already exists",
                "tracking number already exists",
                "duplicate invoice tracking",
                "duplicate travel tracking",
                "duplicate claim tracking"
            ]
        )
    ) {
        return [
            errorCode : "TRACKING_DUPLICATE",
            field     : "documentHeader.invoiceTrackingNumber",
            message   : "Invoice tracking number already exists."
        ]
    }


    /*
     * 30. Authorization
     */
    if (
        containsAny(
            searchableText,
            [
                "not authorized",
                "no authorization",
                "not have authorization",
                "authorization failure",
                "authorization check failed"
            ]
        )
    ) {
        return [
            errorCode : "NOT_AUTHORIZED",
            field     : "documentHeader.username",
            message   : "User is not authorized."
        ]
    }


    /*
     * RW 609 is normally a summary error:
     * "Error in document: BKPFF ..."
     *
     * Preserve it when no more specific classification is available.
     */
    if (
        sapId?.equalsIgnoreCase("RW") &&
        normalizeNumber(sapNumber) == "609"
    ) {
        return [
            errorCode : "SAP_DOCUMENT_ERROR",
            field     : "sapResponse.RETURN",
            message   : messageText ?: "Error occurred while posting the accounting document."
        ]
    }


    /*
     * Unknown SAP errors are never discarded.
     */
    return [
        errorCode : "UNCLASSIFIED_SAP_ERROR",
        field     : mapSapField(
            parameter,
            sapField
        ),
        message   : messageText ?: "SAP returned an unclassified posting error."
    ]
}


/*
 * Determine an input field from SAP PARAMETER/FIELD where possible.
 */
private static String mapSapField(
    String parameter,
    String sapField
) {

    String searchableText =
        "${parameter ?: ""} ${sapField ?: ""}"
            .toLowerCase()


    if (
        containsAny(
            searchableText,
            ["comp_code", "bukrs"]
        )
    ) {
        return "documentHeader.companyCode"
    }

    if (
        containsAny(
            searchableText,
            ["doc_date", "bldat"]
        )
    ) {
        return "documentHeader.documentDate"
    }

    if (
        containsAny(
            searchableText,
            ["pstng_date", "budat"]
        )
    ) {
        return "documentHeader.postingDate"
    }

    if (
        containsAny(
            searchableText,
            ["fisc_year", "gjahr"]
        )
    ) {
        return "documentHeader.fiscalYear"
    }

    if (
        containsAny(
            searchableText,
            ["fis_period", "monat"]
        )
    ) {
        return "documentHeader.fiscalPeriod"
    }

    if (
        containsAny(
            searchableText,
            ["doc_type", "blart"]
        )
    ) {
        return "documentHeader.documentType"
    }

    if (
        containsAny(
            searchableText,
            ["vendor_no", "lifnr"]
        )
    ) {
        return "vendor.vendorNumber"
    }

    if (
        containsAny(
            searchableText,
            ["gl_account", "hkont", "saknr"]
        )
    ) {
        return "expenses.expense.glAccount"
    }

    if (
        containsAny(
            searchableText,
            ["costcenter", "cost_center", "kostl"]
        )
    ) {
        return "expenses.expense.costCenter"
    }

    if (
        containsAny(
            searchableText,
            ["tax_code", "mwskz"]
        )
    ) {
        return "expenses.expense.taxCode"
    }

    if (
        containsAny(
            searchableText,
            ["businessplace", "business_place", "bupla"]
        )
    ) {
        return "vendor.businessPlace"
    }

    if (
        containsAny(
            searchableText,
            ["sectioncode", "section_code", "secco"]
        )
    ) {
        return "vendor.sectionCode"
    }

    if (
        containsAny(
            searchableText,
            ["currency", "waers"]
        )
    ) {
        return "documentHeader.currency"
    }

    if (
        containsAny(
            searchableText,
            ["itemno_acc", "item_number"]
        )
    ) {
        return "itemNumber"
    }

    return "sapResponse.RETURN"
}


/*
 * Check whether text contains any supplied pattern.
 */
private static boolean containsAny(
    String source,
    List<String> patterns
) {

    if (source == null || source.isEmpty()) {
        return false
    }

    return patterns.any { pattern ->

        source.contains(
            pattern.toLowerCase()
        )
    }
}


/*
 * Normalize an SAP message number to three digits.
 */
private static String normalizeNumber(
    String sapNumber
) {

    String number =
        sapNumber?.trim() ?: ""

    if (number.isEmpty()) {
        return ""
    }

    try {
        return String.format(
            "%03d",
            Integer.parseInt(number)
        )

    } catch (Exception ignored) {
        return number
    }
}


/*
 * Sort errors in the required business sequence.
 *
 * The map is local to avoid CPI's:
 * "Variable ERROR_PRIORITY is not used" warning.
 */
private static void sortErrorsByPriority(
    List errors
) {

    Map<String, Integer> errorPriority = [
        "COMPANY_CODE"            : 1,
        "DOCUMENT_DATE"           : 2,
        "POSTING_DATE"            : 3,
        "FISCAL_YEAR_PERIOD"      : 4,
        "DOCUMENT_TYPE"           : 5,
        "VENDOR_NOT_FOUND"        : 6,
        "VENDOR_COMPANY_CODE"     : 7,
        "GL_NOT_FOUND"            : 8,
        "GL_COMPANY_CODE"         : 9,
        "COST_CENTER_NOT_FOUND"   : 10,
        "COST_CENTER_DATE"        : 11,
        "COST_CENTER_MANDATORY"   : 12,
        "TAX_CODE_INVALID"        : 13,
        "TAX_CODE_GL"             : 14,
        "BUSINESS_PLACE"          : 15,
        "SECTION_CODE"            : 16,
        "CURRENCY_INVALID"        : 17,
        "CURRENCY_AMOUNT_MISSING" : 18,
        "DOCUMENT_NOT_BALANCED"   : 19,
        "NO_ITEM_INFORMATION"     : 20,
        "CURRENCY_ITEM_MISMATCH"  : 21,
        "DUPLICATE_ITEM_NUMBER"   : 22,
        "VENDOR_ITEM_MISSING"     : 23,
        "GL_ITEM_MISSING"         : 24,
        "AMOUNT_SIGN"             : 25,
        "AMOUNT_NOT_BALANCED"     : 26,
        "REFERENCE_DUPLICATE"     : 27,
        "TRACKING_DUPLICATE"      : 28,
        "PERIOD_CLOSED"           : 29,
        "NOT_AUTHORIZED"          : 30,

        "SAP_DOCUMENT_ERROR"      : 998,
        "INVALID_SAP_RESPONSE"    : 998,
        "SAP_DOCUMENT_NOT_POSTED" : 998,
        "UNCLASSIFIED_SAP_ERROR"  : 999
    ]


    errors.sort { firstError, secondError ->

        String firstCode =
            firstError.errorCode ?:
                "UNCLASSIFIED_SAP_ERROR"

        String secondCode =
            secondError.errorCode ?:
                "UNCLASSIFIED_SAP_ERROR"


        int firstPriority =
            errorPriority.containsKey(firstCode)
                ? errorPriority[firstCode]
                : errorPriority["UNCLASSIFIED_SAP_ERROR"]

        int secondPriority =
            errorPriority.containsKey(secondCode)
                ? errorPriority[secondCode]
                : errorPriority["UNCLASSIFIED_SAP_ERROR"]


        firstPriority <=> secondPriority
    }
}


/*
 * Build the standardized SAP failure response.
 */
private static Message buildFailureResponse(
    Message message,
    List errors,
    String objectKey,
    String objectSystem,
    String objectType
) {

    sortErrorsByPriority(errors)


    Map failureResponse = [
        status    : "FAILED",
        message   : "Accounting document was not posted.",
        errorCount: errors.size(),
        objectKey : objectKey ?: "",
        objectType: objectType ?: "",
        system    : objectSystem ?: "",
        errors    : errors
    ]


    message.setBody(
        JsonOutput.prettyPrint(
            JsonOutput.toJson(failureResponse)
        )
    )

    message.setHeader(
        "Content-Type",
        "application/json"
    )

    /*
     * RFC transport may return HTTP 200 even when BAPI posting fails.
     * Convert the SAP business failure into HTTP 400.
     */
    message.setHeader(
        "CamelHttpResponseCode",
        400
    )

    message.setProperty(
        "sapPostingSuccessful",
        "false"
    )

    message.setProperty(
        "sapDocumentKey",
        objectKey ?: ""
    )

    return message
}


/*
 * Extract the first ten characters of the BAPI object key.
 *
 * Example:
 * OBJ_KEY = 261900001620002026
 * Document number = 2619000016
 */
private static String extractDocumentNumber(
    String objectKey
) {

    String normalizedKey =
        objectKey?.trim() ?: ""

    if (normalizedKey.length() >= 10) {
        return normalizedKey.substring(0, 10)
    }

    return normalizedKey
}