import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {
    try {
        def reader = message.getBody(java.io.Reader.class)
        def factory = new XmlSlurper()
        // Harden against XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        
        def xml = factory.parse(reader)

        def returnType = xml.RETURN.TYPE.text()
        def returnMessage = xml.RETURN.MESSAGE.text()

        if (returnType == 'E' || returnType == '') {
            message.setProperty("SAP_USER_VALID", "false")
            message.setProperty("SAP_USER_ERROR_MSG", returnMessage ?: "User not registered or not authorized in SAP")
        } else {
            message.setProperty("SAP_USER_VALID", "true")
        }
    } catch (Exception e) {
        message.setProperty("SAP_USER_VALID", "false")
        message.setProperty("SAP_USER_ERROR_MSG", "User validation check failed: ${e.message}")
    }
    return message
}