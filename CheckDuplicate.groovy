import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import com.sap.it.api.asdk.datastore.DataStoreService
import com.sap.it.api.asdk.runtime.Factory

def Message processData(Message message) {

    java.io.Reader reader =
        message.getBody(java.io.Reader.class)

    def json =
        new JsonSlurper().parse(reader)

    String referenceDocumentNumber =
        json.documentHeader
            ?.referenceDocumentNumber
            ?.toString()
            ?.trim()

    // Reader is consumed now — restore the body as JSON string
    // so Field_Mapping can still read/parse it downstream
    message.setBody(
        JsonOutput.toJson(json)
    )

    if (referenceDocumentNumber) {

        def dataStoreService =
            new Factory(DataStoreService.class).getService()

        if (dataStoreService != null) {

            def existingEntry =
                dataStoreService.get(
                    "ProcessedDocumentRefs",
                    referenceDocumentNumber
                )

            if (existingEntry != null) {

                def response = [
                    status  : "FAILED",
                    message : "duplicate document reference number"
                ]

                message.setBody(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(response)
                    )
                )
                message.setHeader("Content-Type", "application/json")
                message.setHeader("CamelHttpResponseCode", 409)
                message.setProperty("isDuplicate", "true")

                return message
            }
        }
    }

    message.setProperty("referenceDocumentNumber", referenceDocumentNumber ?: "")
    message.setProperty("isDuplicate", "false")

    return message
}