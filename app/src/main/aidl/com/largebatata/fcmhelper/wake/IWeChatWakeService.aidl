package com.largebatata.fcmhelper.wake;

interface IWeChatWakeService {
    int startCoreService() = 1;
    void destroy() = 16777114;
}
