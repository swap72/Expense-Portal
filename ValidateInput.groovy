import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle


/*
 * Error priorities follow the required business sequence.
 *
 * SAP-dependent errors such as vendor existence, G/L master validity,
 * posting-period status and authorization are handled later by
 * HandleSapResponse.groovy.
 */

/*
 * Sort validation errors according to the required business sequence.
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

        "REQUEST_BODY"            : 100,
        "ROOT_STRUCTURE"          : 101,
        "OBJECT_TYPE"             : 102,
        "USERNAME"                : 103,
        "REFERENCE_DOCUMENT"      : 104,
        "INVOICE_TRACKING"        : 105,
        "VENDOR_ITEM_NUMBER"      : 106,
        "EXPENSE_STRUCTURE"       : 107,
        "EXPENSE_ITEM_NUMBER"     : 108,
        "EXPENSE_COMPANY_CODE"    : 109,
        "ITEM_TEXT"               : 110,
        "ASSIGNMENT"              : 111,
        "CURRENCY_ITEM_NUMBER"    : 112,
        "INVALID_AMOUNT"          : 113,
        "UNCLASSIFIED_ERROR"      : 999
    ]


    errors.sort { firstError, secondError ->

        String firstCode =
            firstError.errorCode ?: "UNCLASSIFIED_ERROR"

        String secondCode =
            secondError.errorCode ?: "UNCLASSIFIED_ERROR"


        int firstPriority =
            errorPriority.containsKey(firstCode)
                ? errorPriority[firstCode]
                : errorPriority["UNCLASSIFIED_ERROR"]

        int secondPriority =
            errorPriority.containsKey(secondCode)
                ? errorPriority[secondCode]
                : errorPriority["UNCLASSIFIED_ERROR"]


        firstPriority <=> secondPriority
    }
}

def Message processData(Message message) {

    def json

    /*
     * Read incoming JSON using streaming.
     */
    try {
        java.io.Reader reader =
            message.getBody(java.io.Reader.class)

        json =
            new JsonSlurper().parse(reader)

    } catch (Exception ignored) {

        List invalidJsonErrors = [[
            errorCode : "REQUEST_BODY",
            field     : "requestBody",
            message   : "Request body is not valid JSON."
        ]]

        return buildValidationErrorResponse(
            message,
            invalidJsonErrors,
            "Invalid JSON payload."
        )
    }


    List errors = []


    /*
     * ============================================================
     * VALIDATION HELPERS
     * ============================================================
     */

    def isBlank = { value ->

        value == null ||
        value.toString().trim().isEmpty()
    }


    def addError = {
        String errorCode,
        String field,
        String errorMessage ->

        errors << [
            errorCode : errorCode,
            field     : field,
            message   : errorMessage
        ]
    }


    def mandatory = {
        value,
        String field,
        String errorCode ->

        if (isBlank(value)) {
            addError(
                errorCode,
                field,
                "${field} is mandatory."
            )
        }
    }


    def maxLength = {
        value,
        String field,
        int maximumLength,
        String errorCode ->

        if (
            !isBlank(value) &&
            value.toString().length() > maximumLength
        ) {
            addError(
                errorCode,
                field,
                "${field} cannot exceed ${maximumLength} characters."
            )
        }
    }


    def exactLength = {
        value,
        String field,
        int requiredLength,
        String errorCode ->

        if (
            !isBlank(value) &&
            value.toString().length() != requiredLength
        ) {
            addError(
                errorCode,
                field,
                "${field} must contain exactly ${requiredLength} characters."
            )
        }
    }


    def matchesPattern = {
        value,
        String field,
        String regex,
        String expectedFormat,
        String errorCode ->

        if (
            !isBlank(value) &&
            !value.toString().matches(regex)
        ) {
            addError(
                errorCode,
                field,
                "${field} ${expectedFormat}."
            )
        }
    }


    def validateDateYYYYMMDD = {
        value,
        String field,
        String errorCode ->

        if (isBlank(value)) {
            return
        }

        String dateValue =
            value.toString().trim()

        if (!dateValue.matches("\\d{8}")) {
            addError(
                errorCode,
                field,
                "${field} must be in yyyyMMdd format."
            )

            return
        }

        try {
            DateTimeFormatter formatter =
                DateTimeFormatter
                    .ofPattern("uuuuMMdd")
                    .withResolverStyle(
                        ResolverStyle.STRICT
                    )

            LocalDate.parse(
                dateValue,
                formatter
            )

        } catch (Exception ignored) {
            addError(
                errorCode,
                field,
                "${field} contains an invalid date."
            )
        }
    }


    /*
     * ============================================================
     * ROOT STRUCTURE VALIDATION
     * ============================================================
     */

    if (!(json instanceof Map)) {

        addError(
            "ROOT_STRUCTURE",
            "requestBody",
            "JSON root must be a JSON object."
        )

        return buildValidationErrorResponse(
            message,
            errors,
            "Payload validation failed."
        )
    }


    if (!(json.documentHeader instanceof Map)) {
        addError(
            "ROOT_STRUCTURE",
            "documentHeader",
            "documentHeader structure is mandatory."
        )
    }

    if (!(json.vendor instanceof Map)) {
        addError(
            "VENDOR_ITEM_MISSING",
            "vendor",
            "vendor structure is mandatory."
        )
    }

    if (!(json.expenses instanceof Map)) {
        addError(
            "GL_ITEM_MISSING",
            "expenses",
            "expenses structure is mandatory."
        )
    }

    if (!(json.currencyAmounts instanceof Map)) {
        addError(
            "CURRENCY_AMOUNT_MISSING",
            "currencyAmounts",
            "currencyAmounts structure is mandatory."
        )
    }


    /*
     * ============================================================
     * DOCUMENT HEADER VALIDATION
     * ============================================================
     */

    if (json.documentHeader instanceof Map) {

        def header =
            json.documentHeader


        mandatory(
            header.objType,
            "documentHeader.objType",
            "OBJECT_TYPE"
        )

        mandatory(
            header.username,
            "documentHeader.username",
            "USERNAME"
        )

        mandatory(
            header.companyCode,
            "documentHeader.companyCode",
            "COMPANY_CODE"
        )

        mandatory(
            header.documentDate,
            "documentHeader.documentDate",
            "DOCUMENT_DATE"
        )

        mandatory(
            header.postingDate,
            "documentHeader.postingDate",
            "POSTING_DATE"
        )

        mandatory(
            header.referenceDocumentNumber,
            "documentHeader.referenceDocumentNumber",
            "REFERENCE_DOCUMENT"
        )

        mandatory(
            header.fiscalYear,
            "documentHeader.fiscalYear",
            "FISCAL_YEAR_PERIOD"
        )

        mandatory(
            header.fiscalPeriod,
            "documentHeader.fiscalPeriod",
            "FISCAL_YEAR_PERIOD"
        )

        mandatory(
            header.documentType,
            "documentHeader.documentType",
            "DOCUMENT_TYPE"
        )

        mandatory(
            header.invoiceTrackingNumber,
            "documentHeader.invoiceTrackingNumber",
            "INVOICE_TRACKING"
        )

        mandatory(
            header.currency,
            "documentHeader.currency",
            "CURRENCY_INVALID"
        )


        maxLength(
            header.objType,
            "documentHeader.objType",
            5,
            "OBJECT_TYPE"
        )

        maxLength(
            header.username,
            "documentHeader.username",
            12,
            "USERNAME"
        )

        exactLength(
            header.companyCode,
            "documentHeader.companyCode",
            4,
            "COMPANY_CODE"
        )

        matchesPattern(
            header.companyCode,
            "documentHeader.companyCode",
            "\\d{4}",
            "must contain exactly 4 numeric digits",
            "COMPANY_CODE"
        )


        /*
         * Company code must be between 0001 and 9000.
         */
        if (
            !isBlank(header.companyCode) &&
            header.companyCode.toString().matches("\\d{4}")
        ) {
            int companyCodeValue =
                Integer.parseInt(
                    header.companyCode.toString()
                )

            if (
                companyCodeValue < 1 ||
                companyCodeValue > 9000
            ) {
                addError(
                    "COMPANY_CODE",
                    "documentHeader.companyCode",
                    "documentHeader.companyCode must be between 0001 and 9000."
                )
            }
        }


        maxLength(
            header.referenceDocumentNumber,
            "documentHeader.referenceDocumentNumber",
            16,
            "REFERENCE_DOCUMENT"
        )

        exactLength(
            header.documentType,
            "documentHeader.documentType",
            2,
            "DOCUMENT_TYPE"
        )

        maxLength(
            header.invoiceTrackingNumber,
            "documentHeader.invoiceTrackingNumber",
            12,
            "INVOICE_TRACKING"
        )

        exactLength(
            header.currency,
            "documentHeader.currency",
            3,
            "CURRENCY_INVALID"
        )


        validateDateYYYYMMDD(
            header.documentDate,
            "documentHeader.documentDate",
            "DOCUMENT_DATE"
        )

        validateDateYYYYMMDD(
            header.postingDate,
            "documentHeader.postingDate",
            "POSTING_DATE"
        )


        matchesPattern(
            header.fiscalYear,
            "documentHeader.fiscalYear",
            "\\d{4}",
            "must contain exactly 4 numeric digits",
            "FISCAL_YEAR_PERIOD"
        )

        matchesPattern(
            header.fiscalPeriod,
            "documentHeader.fiscalPeriod",
            "\\d{2}",
            "must contain exactly 2 numeric digits",
            "FISCAL_YEAR_PERIOD"
        )

        matchesPattern(
            header.currency,
            "documentHeader.currency",
            "[A-Z]{3}",
            "must contain exactly 3 uppercase alphabetic characters",
            "CURRENCY_INVALID"
        )
    }


    /*
     * ============================================================
     * VENDOR VALIDATION
     * ============================================================
     */

    if (json.vendor instanceof Map) {

        def vendor =
            json.vendor


        mandatory(
            vendor.itemNumber,
            "vendor.itemNumber",
            "VENDOR_ITEM_MISSING"
        )

        mandatory(
            vendor.vendorNumber,
            "vendor.vendorNumber",
            "VENDOR_NOT_FOUND"
        )

        mandatory(
            vendor.businessPlace,
            "vendor.businessPlace",
            "BUSINESS_PLACE"
        )

        mandatory(
            vendor.sectionCode,
            "vendor.sectionCode",
            "SECTION_CODE"
        )


        matchesPattern(
            vendor.itemNumber,
            "vendor.itemNumber",
            "\\d{10}",
            "must contain exactly 10 numeric digits",
            "VENDOR_ITEM_NUMBER"
        )

        maxLength(
            vendor.vendorNumber,
            "vendor.vendorNumber",
            10,
            "VENDOR_NOT_FOUND"
        )

        maxLength(
            vendor.assignment,
            "vendor.assignment",
            18,
            "ASSIGNMENT"
        )

        maxLength(
            vendor.businessPlace,
            "vendor.businessPlace",
            4,
            "BUSINESS_PLACE"
        )

        maxLength(
            vendor.sectionCode,
            "vendor.sectionCode",
            4,
            "SECTION_CODE"
        )
    }


    /*
     * ============================================================
     * NORMALIZE EXPENSE STRUCTURE
     * ============================================================
     */

    List expenseList = []

    if (json.expenses instanceof Map) {

        def expenseNode =
            json.expenses.expense

        if (expenseNode instanceof Collection) {
            expenseList.addAll(expenseNode)

        } else if (expenseNode instanceof Map) {
            expenseList.add(expenseNode)

        } else if (expenseNode != null) {
            addError(
                "GL_ITEM_MISSING",
                "expenses.expense",
                "expenses.expense must be a JSON object or array."
            )
        }


        if (
            expenseNode == null ||
            (
                expenseNode instanceof Collection &&
                expenseNode.isEmpty()
            )
        ) {
            addError(
                "GL_ITEM_MISSING",
                "expenses.expense",
                "At least one expense record is required."
            )
        }
    }


    /*
     * ============================================================
     * EXPENSE VALIDATION
     * ============================================================
     */

    expenseList.eachWithIndex { expense, index ->

        String prefix =
            "expenses.expense[${index}]"

        if (!(expense instanceof Map)) {
            addError(
                "GL_ITEM_MISSING",
                prefix,
                "Expense record must be a JSON object."
            )

            return
        }


        mandatory(
            expense.itemNumber,
            "${prefix}.itemNumber",
            "GL_ITEM_MISSING"
        )

        mandatory(
            expense.glAccount,
            "${prefix}.glAccount",
            "GL_NOT_FOUND"
        )

        mandatory(
            expense.costCenter,
            "${prefix}.costCenter",
            "COST_CENTER_MANDATORY"
        )

        mandatory(
            expense.companyCode,
            "${prefix}.companyCode",
            "GL_COMPANY_CODE"
        )


        matchesPattern(
            expense.itemNumber,
            "${prefix}.itemNumber",
            "\\d{10}",
            "must contain exactly 10 numeric digits",
            "EXPENSE_ITEM_NUMBER"
        )

        maxLength(
            expense.glAccount,
            "${prefix}.glAccount",
            10,
            "GL_NOT_FOUND"
        )

        maxLength(
            expense.itemText,
            "${prefix}.itemText",
            50,
            "ITEM_TEXT"
        )

        maxLength(
            expense.assignment,
            "${prefix}.assignment",
            18,
            "ASSIGNMENT"
        )

        maxLength(
            expense.costCenter,
            "${prefix}.costCenter",
            10,
            "COST_CENTER_NOT_FOUND"
        )

        exactLength(
            expense.companyCode,
            "${prefix}.companyCode",
            4,
            "GL_COMPANY_CODE"
        )


        /*
         * Expense company code must match header company code.
         */
        if (
            json.documentHeader instanceof Map &&
            !isBlank(json.documentHeader.companyCode) &&
            !isBlank(expense.companyCode) &&
            expense.companyCode.toString() !=
                json.documentHeader.companyCode.toString()
        ) {
            addError(
                "GL_COMPANY_CODE",
                "${prefix}.companyCode",
                "${prefix}.companyCode must match documentHeader.companyCode."
            )
        }
    }


    /*
     * ============================================================
     * NORMALIZE CURRENCY-AMOUNT STRUCTURE
     * ============================================================
     */

    List currencyAmountList = []

    if (json.currencyAmounts instanceof Map) {

        def currencyAmountNode =
            json.currencyAmounts.currencyAmount

        if (currencyAmountNode instanceof Collection) {
            currencyAmountList.addAll(currencyAmountNode)

        } else if (currencyAmountNode instanceof Map) {
            currencyAmountList.add(currencyAmountNode)

        } else if (currencyAmountNode != null) {
            addError(
                "CURRENCY_AMOUNT_MISSING",
                "currencyAmounts.currencyAmount",
                "currencyAmounts.currencyAmount must be a JSON object or array."
            )
        }


        if (
            currencyAmountNode == null ||
            (
                currencyAmountNode instanceof Collection &&
                currencyAmountNode.isEmpty()
            )
        ) {
            addError(
                "CURRENCY_AMOUNT_MISSING",
                "currencyAmounts.currencyAmount",
                "At least one currency amount record is required."
            )
        }
    }


    /*
     * ============================================================
     * CURRENCY-AMOUNT VALIDATION
     * ============================================================
     */

    currencyAmountList.eachWithIndex {
        currencyAmount,
        index ->

        String prefix =
            "currencyAmounts.currencyAmount[${index}]"

        if (!(currencyAmount instanceof Map)) {
            addError(
                "CURRENCY_AMOUNT_MISSING",
                prefix,
                "Currency amount record must be a JSON object."
            )

            return
        }


        mandatory(
            currencyAmount.itemNumber,
            "${prefix}.itemNumber",
            "CURRENCY_ITEM_NUMBER"
        )

        mandatory(
            currencyAmount.currency,
            "${prefix}.currency",
            "CURRENCY_INVALID"
        )

        mandatory(
            currencyAmount.amount,
            "${prefix}.amount",
            "CURRENCY_AMOUNT_MISSING"
        )


        matchesPattern(
            currencyAmount.itemNumber,
            "${prefix}.itemNumber",
            "\\d{10}",
            "must contain exactly 10 numeric digits",
            "CURRENCY_ITEM_NUMBER"
        )

        exactLength(
            currencyAmount.currency,
            "${prefix}.currency",
            3,
            "CURRENCY_INVALID"
        )

        matchesPattern(
            currencyAmount.currency,
            "${prefix}.currency",
            "[A-Z]{3}",
            "must contain exactly 3 uppercase alphabetic characters",
            "CURRENCY_INVALID"
        )


        if (
            json.documentHeader instanceof Map &&
            !isBlank(json.documentHeader.currency) &&
            !isBlank(currencyAmount.currency) &&
            currencyAmount.currency.toString() !=
                json.documentHeader.currency.toString()
        ) {
            addError(
                "CURRENCY_INVALID",
                "${prefix}.currency",
                "${prefix}.currency must match documentHeader.currency."
            )
        }


        if (!isBlank(currencyAmount.amount)) {
            try {
                new BigDecimal(
                    currencyAmount.amount.toString()
                )
            } catch (Exception ignored) {
                addError(
                    "INVALID_AMOUNT",
                    "${prefix}.amount",
                    "${prefix}.amount must contain a valid numeric value."
                )
            }
        }
    }


    /*
     * ============================================================
     * ACCOUNTING ITEM-NUMBER VALIDATION
     * ============================================================
     */

    Set<String> accountingItemNumbers =
        new LinkedHashSet<String>()


    /*
     * Vendor accounting item.
     */
    if (
        json.vendor instanceof Map &&
        !isBlank(json.vendor.itemNumber)
    ) {
        accountingItemNumbers.add(
            json.vendor.itemNumber.toString()
        )
    }


    /*
     * Expense accounting items and duplicate detection.
     */
    expenseList.eachWithIndex { expense, index ->

        if (
            expense instanceof Map &&
            !isBlank(expense.itemNumber)
        ) {
            String itemNumber =
                expense.itemNumber.toString()

            if (accountingItemNumbers.contains(itemNumber)) {
                addError(
                    "DUPLICATE_ITEM_NUMBER",
                    "expenses.expense[${index}].itemNumber",
                    "Duplicate line item number ${itemNumber} found."
                )

            } else {
                accountingItemNumbers.add(itemNumber)
            }
        }
    }


    /*
     * ============================================================
     * CURRENCY ITEM-NUMBER VALIDATION
     * ============================================================
     */

    Set<String> currencyItemNumbers =
        new LinkedHashSet<String>()


    currencyAmountList.eachWithIndex {
        currencyAmount,
        index ->

        if (
            currencyAmount instanceof Map &&
            !isBlank(currencyAmount.itemNumber)
        ) {
            String itemNumber =
                currencyAmount.itemNumber.toString()

            if (currencyItemNumbers.contains(itemNumber)) {
                addError(
                    "DUPLICATE_ITEM_NUMBER",
                    "currencyAmounts.currencyAmount[${index}].itemNumber",
                    "Duplicate currency amount item number ${itemNumber} found."
                )

            } else {
                currencyItemNumbers.add(itemNumber)
            }


            if (!accountingItemNumbers.contains(itemNumber)) {
                addError(
                    "CURRENCY_ITEM_MISMATCH",
                    "currencyAmounts.currencyAmount[${index}].itemNumber",
                    "Currency amount item number ${itemNumber} does not match any accounting item."
                )
            }
        }
    }


    /*
     * Ensure every accounting item has a currency amount.
     */
    accountingItemNumbers.each { itemNumber ->

        if (!currencyItemNumbers.contains(itemNumber)) {
            addError(
                "CURRENCY_AMOUNT_MISSING",
                "currencyAmounts.currencyAmount",
                "Currency amount is missing for accounting item ${itemNumber}."
            )
        }
    }


    /*
     * ============================================================
     * AMOUNT-SIGN AND BALANCING VALIDATION
     * ============================================================
     */

    if (!currencyAmountList.isEmpty()) {

        BigDecimal totalAmount =
            BigDecimal.ZERO

        String vendorItemNumber =
            json.vendor instanceof Map &&
            !isBlank(json.vendor.itemNumber)
                ? json.vendor.itemNumber.toString()
                : null


        currencyAmountList.eachWithIndex {
            currencyAmount,
            index ->

            if (
                currencyAmount instanceof Map &&
                !isBlank(currencyAmount.amount)
            ) {
                try {
                    BigDecimal amount =
                        new BigDecimal(
                            currencyAmount.amount.toString()
                        )

                    totalAmount =
                        totalAmount.add(amount)

                    String itemNumber =
                        !isBlank(currencyAmount.itemNumber)
                            ? currencyAmount.itemNumber.toString()
                            : null


                    /*
                     * For document type KR:
                     * vendor amount must be negative;
                     * expense amounts must be positive.
                     */
                    if (
                        vendorItemNumber != null &&
                        itemNumber == vendorItemNumber &&
                        amount.compareTo(BigDecimal.ZERO) >= 0
                    ) {
                        addError(
                            "AMOUNT_SIGN",
                            "currencyAmounts.currencyAmount[${index}].amount",
                            "Vendor currency amount must be negative."
                        )
                    }


                    if (
                        itemNumber != null &&
                        expenseList.any {
                            expense ->
                                expense instanceof Map &&
                                !isBlank(expense.itemNumber) &&
                                expense.itemNumber.toString() ==
                                    itemNumber
                        } &&
                        amount.compareTo(BigDecimal.ZERO) <= 0
                    ) {
                        addError(
                            "AMOUNT_SIGN",
                            "currencyAmounts.currencyAmount[${index}].amount",
                            "Expense currency amount must be positive."
                        )
                    }

                } catch (Exception ignored) {
                    /*
                     * Invalid numeric amount was already reported above.
                     */
                }
            }
        }


        if (totalAmount.compareTo(BigDecimal.ZERO) != 0) {
            addError(
                "DOCUMENT_NOT_BALANCED",
                "currencyAmounts.currencyAmount",
                "Document is not balanced. Currency amounts must total zero."
            )
        }
    }


    /*
     * ============================================================
     * RETURN VALIDATION RESULT
     * ============================================================
     */

    if (!errors.isEmpty()) {

        sortErrorsByPriority(errors)

        return buildValidationErrorResponse(
            message,
            errors,
            "Payload validation failed."
        )
    }


    /*
     * Restore the JSON body because the Reader was consumed.
     */
    message.setBody(
        JsonOutput.toJson(json)
    )

    message.setProperty(
        "validationFailed",
        "false"
    )

    return message
}


/*
 * Sort validation errors according to the required business sequence.
 *
 * Groovy's stable sort preserves the original validation order when two
 * errors have the same priority.
 */
// private static void sortErrorsByPriority(
//     List errors
// ) {

//     errors.sort { firstError, secondError ->

//         int firstPriority =
//             ERROR_PRIORITY.containsKey(
//                 firstError.errorCode
//             )
//                 ? ERROR_PRIORITY[firstError.errorCode]
//                 : ERROR_PRIORITY["UNCLASSIFIED_ERROR"]

//         int secondPriority =
//             ERROR_PRIORITY.containsKey(
//                 secondError.errorCode
//             )
//                 ? ERROR_PRIORITY[secondError.errorCode]
//                 : ERROR_PRIORITY["UNCLASSIFIED_ERROR"]

//         firstPriority <=> secondPriority
//     }
// }


/*
 * Build the validation failure response.
 */
private static Message buildValidationErrorResponse(
    Message message,
    List errors,
    String responseMessage
) {

    /*
     * Sorting here also covers invalid/root payload responses.
     */
    sortErrorsByPriority(errors)


    Map response = [
        status     : "FAILED",
        message    : responseMessage,
        errorCount : errors.size(),
        errors     : errors
    ]


    message.setBody(
        JsonOutput.prettyPrint(
            JsonOutput.toJson(response)
        )
    )

    message.setHeader(
        "Content-Type",
        "application/json"
    )

    message.setHeader(
        "CamelHttpResponseCode",
        400
    )

    message.setProperty(
        "validationFailed",
        "true"
    )

    return message
}
