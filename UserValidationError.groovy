import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonOutput

def Message processData(Message message) {

    def username = message.getProperty("username") ?: ""

    def errorMsg = message.getProperty("ErrorMessage") ?:
        "User not registered or not authorized in SAP"

    def response = [
        status   : "FAILED",
        errorType: "USER_VALIDATION",
        username : username.toString(),
        message  : errorMsg.toString()
    ]

    message.setBody(JsonOutput.toJson(response))
    message.setHeader("Content-Type", "application/json")
    message.setHeader("CamelHttpResponseCode", 403)

    return message
}