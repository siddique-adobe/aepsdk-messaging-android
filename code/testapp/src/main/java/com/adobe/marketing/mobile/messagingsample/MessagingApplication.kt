/*
 Copyright 2021 Adobe. All rights reserved.
 This file is licensed to you under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License. You may obtain a copy
 of the License at http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software distributed under
 the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 OF ANY KIND, either express or implied. See the License for the specific language
 governing permissions and limitations under the License.
 */

package com.adobe.marketing.mobile.messagingsample

import android.app.Application
import com.adobe.marketing.mobile.Assurance
import com.adobe.marketing.mobile.Edge
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.LoggingMode
import com.adobe.marketing.mobile.Lifecycle
import com.adobe.marketing.mobile.edge.identity.Identity

class MessagingApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        MobileCore.setApplication(this)
        MobileCore.setLogLevel(LoggingMode.VERBOSE)
        val extensions = listOf(Messaging.EXTENSION, Identity.EXTENSION, Lifecycle.EXTENSION, Edge.EXTENSION, Assurance.EXTENSION)
        MobileCore.registerExtensions(extensions) {
            // Necessary property id which has the edge configuration id needed by aep sdk
            //MobileCore.configureWithAppID("staging/1b50a869c4a2/f3593b4ab236/launch-5d384d7e591f-development")
            MobileCore.configureWithAppID("3149c49c3910/4f6b2fbf2986/launch-7d78a5fd1de3-development")
            MobileCore.lifecycleStart(null)

            val configMap = mapOf(
                "messaging.optimizePushSync" to true
            )
            MobileCore.updateConfiguration(configMap)
        }
        //Assurance.startSession("hello://?adb_validation_sessionid=e5f73dd1-bce5-43e2-ac7e-2095d6a9784b&env=qa") 2815
        Assurance.startSession("messaging://?adb_validation_sessionid=23ad3126-a273-4b27-9c6b-3672b3184035")
    }
}