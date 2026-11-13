import com.sap.gateway.ip.core.customdev.util.Message
import com.sap.it.api.asdk.datastore.DataStoreService
import com.sap.it.api.asdk.datastore.DataBean
import com.sap.it.api.asdk.datastore.DataConfig
import com.sap.it.api.asdk.runtime.Factory

import groovy.json.JsonOutput


def Message processData(Message message) {

    /*
     * ============================================================
     * VERIFY SAP POSTING STATUS
     * ============================================================
     *
     * HandleSapResponse.groovy creates this exchange property.
     * Mark_Processed must execute only for successful SAP posting.
     */

    String sapPostingSuccessful =
        message.getProperty(
            "sapPostingSuccessful"
        )?.toString()


    if (sapPostingSuccessful != "true") {
        return message
    }


    /*
     * ============================================================
     * READ EXCHANGE PROPERTIES
     * ============================================================
     */

    String referenceDocumentNumber =
        message.getProperty(
            "referenceDocumentNumber"
        )?.toString()?.trim()

    String objectKey =
        message.getProperty(
            "sapDocumentKey"
        )?.toString()?.trim()

    String documentNumber =
        message.getProperty(
            "sapDocumentNumber"
        )?.toString()?.trim()


    /*
     * ============================================================
     * MARK REFERENCE DOCUMENT AS PROCESSED
     * ============================================================
     */

    if (
        referenceDocumentNumber != null &&
        !referenceDocumentNumber.isEmpty()
    ) {
        DataStoreService dataStoreService =
            new Factory(
                DataStoreService.class
            ).getService()


        if (dataStoreService != null) {

            DataBean dataBean =
                new DataBean()

            dataBean.setDataAsArray(
                referenceDocumentNumber.getBytes(
                    "UTF-8"
                )
            )


            DataConfig dataConfig =
                new DataConfig()

            dataConfig.setStoreName(
                "ProcessedDocumentRefs"
            )

            dataConfig.setId(
                referenceDocumentNumber
            )

            dataConfig.setOverwrite(
                false
            )


            dataStoreService.put(
                dataBean,
                dataConfig
            )
        }
    }


    /*
     * ============================================================
     * EXTRACT VALUES FROM SAP OBJECT KEY
     * ============================================================
     *
     * Example object key:
     * 261900001920002026
     *
     * First 10 characters = Document number
     * Next 4 characters   = Company code
     * Last 4 characters   = Fiscal year
     */

    String companyCode = ""
    String fiscalYear = ""


    if (
        objectKey != null &&
        objectKey.length() >= 18
    ) {
        if (
            documentNumber == null ||
            documentNumber.isEmpty()
        ) {
            documentNumber =
                objectKey.substring(
                    0,
                    10
                )
        }

        companyCode =
            objectKey.substring(
                10,
                14
            )

        fiscalYear =
            objectKey.substring(
                14,
                18
            )
    }


    documentNumber =
        documentNumber ?: ""


    /*
     * ============================================================
     * BUILD JSON SUCCESS RESPONSE
     * ============================================================
     */

    Map successResponse = [
        status         : "success",
        message        : "Document posted successfully",
        "documentNo" : documentNumber,
        "companyCode" : companyCode,
        "fiscalYear"  : fiscalYear
    ]


    message.setBody(
        JsonOutput.prettyPrint(
            JsonOutput.toJson(
                successResponse
            )
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

    return message
}