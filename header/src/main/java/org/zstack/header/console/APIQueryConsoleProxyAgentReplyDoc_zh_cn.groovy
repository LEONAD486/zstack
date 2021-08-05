package org.zstack.header.console

import org.zstack.header.errorcode.ErrorCode

doc {

    title "控制台代理Agent清单列表"

	field {
		name "success"
		desc ""
		type "boolean"
		since "0.6"
	}
    ref {
        name "error"
        path "org.zstack.header.console.APIQueryConsoleProxyAgentReply.error"
        desc "错误码，若不为null，则表示操作失败, 操作成功时该字段为null", false
        type "ErrorCode"
        since "0.6"
        clz ErrorCode.class
    }
    ref {
        name "inventories"
        path "org.zstack.header.console.APIQueryConsoleProxyAgentReply.inventories"
        desc "控制台代理Agent清单列表"
        type "List"
        since "0.6"
        clz ConsoleProxyAgentInventory.class
    }
}
