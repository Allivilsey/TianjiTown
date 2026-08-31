package cn.tianji.town.paper;

import cn.tianji.town.storage.town.ApplicationSnapshot;

record ProvisionResult(Status status, ApplicationSnapshot application, String detail,
                       String recoveryAction) {
    enum Status {
        SUCCESS,
        FAILED,
        BUSY,
        TIMEOUT
    }

    static ProvisionResult success(ApplicationSnapshot application) {
        return new ProvisionResult(Status.SUCCESS, application, "小镇创建完成。", "");
    }

    static ProvisionResult failure(ApplicationSnapshot application, String detail,
                                   String recoveryAction) {
        return new ProvisionResult(Status.FAILED, application, detail, recoveryAction);
    }

    static ProvisionResult busy(String detail) {
        return new ProvisionResult(Status.BUSY, null, detail, "返回审核列表后刷新状态");
    }

    static ProvisionResult timeout(ApplicationSnapshot application) {
        return new ProvisionResult(Status.TIMEOUT, application,
                "服务器在限定时间内没有返回最终结果。", "返回审核列表并刷新状态；不要重复扣费");
    }
}
