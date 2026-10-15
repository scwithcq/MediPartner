"""由 Controller 源码生成 docs/api-endpoints.json（机器可读接口清单）。

用途：给前端/模型批量查阅接口，避免手抄产生偏差。
接口有任何变动，重跑本脚本即可。

用法：python tools/gen_api_endpoints.py
"""
import json
import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULE_ROOT = os.path.join(REPO, "src", "main", "java", "com", "dsc", "medipartner", "module")
OUT = os.path.join(REPO, "docs", "api-endpoints.json")

# 分组与权限：按 Controller 类名映射（权限取自 SecurityConfig 的路由前缀规则）
CLASS_MAP = {
    "AuthController": ("认证", "匿名（除登录外需带 token）"),
    "PasswordController": ("认证-密码", "匿名 / 已登录"),
    "UserController": ("用户资料与入驻", "ROLE_USER"),
    "WorkerBrowseController": ("陪诊师浏览", "ROLE_USER"),
    "WorkerProfileController": ("陪诊师工作台", "ROLE_WORKER"),
    "OrderController": ("订单-求助者", "ROLE_USER"),
    "WorkerOrderController": ("订单-陪诊师", "ROLE_WORKER"),
    "OrderReviewController": ("评价-订单", "ROLE_USER"),
    "WorkerReviewController": ("评价-陪诊师", "ROLE_WORKER"),
    "OrderAppealController": ("申诉", "ROLE_USER"),
    "WalletController": ("钱包", "任意已登录"),
    "AiController": ("AI 发单助手", "ROLE_USER"),
    "DemandController": ("需求工单", "ROLE_USER"),
    "OrderChatController": ("订单聊天", "任意已登录（按订单归属校验）"),
    "AdminChatController": ("管理端-聊天查阅", "ROLE_ADMIN（阶段7，未启用）"),
    "CommonDictController": ("公共-字典与配置", "匿名 / 任意已登录"),
    "FileController": ("公共-文件上传", "任意已登录"),
}

# 明确标注未实现/未启用的接口
NOT_READY = {"/api/admin/"}


def main():
    endpoints = []
    for dirpath, _, files in os.walk(MODULE_ROOT):
        for fn in files:
            if not fn.endswith("Controller.java"):
                continue
            src = open(os.path.join(dirpath, fn), encoding="utf-8").read()
            cls = fn[:-5]
            base_m = re.search(r'@RequestMapping\("([^"]+)"\)', src)
            base = base_m.group(1) if base_m else ""
            group, auth = CLASS_MAP.get(cls, (cls, "?"))

            pattern = (r'@(Get|Post|Put|Delete)Mapping(?:\(([^)]*)\))?'
                       r'\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*'
                       r'public\s+([\w<>,\s\.\[\]\?]+?)\s+(\w+)\s*\(([^)]*)\)')
            for m in re.finditer(pattern, src):
                method = m.group(1).upper()
                arg = m.group(2) or ""
                sub_m = re.search(r'"([^"]*)"', arg)
                sub = sub_m.group(1) if sub_m else ""
                path = base + sub
                returns = m.group(3).strip()
                # 请求体类型（@RequestBody X）
                body_m = re.search(r'@RequestBody(?:\([^)]*\))?\s+([\w<>\.]+)\s+\w+', m.group(5))
                body = body_m.group(1) if body_m else None
                endpoints.append({
                    "group": group,
                    "auth": auth,
                    "method": method,
                    "path": path,
                    "requestBody": body,
                    "returns": returns,
                    "controller": cls,
                    "implemented": not any(path.startswith(p) for p in NOT_READY),
                })

    endpoints.sort(key=lambda e: (e["path"], e["method"]))

    out = {
        "project": "MediPartner（医伴）",
        "docVersion": "2.0",
        "generatedFrom": "Controller 源码自动提取，与代码一致",
        "baseUrl": "http://localhost:18080",
        "note": "8080 被其它服务占用，本地联调固定用 18080",
        "totalEndpoints": len(endpoints),
        "implementedEndpoints": sum(1 for e in endpoints if e["implemented"]),
        "authHeader": "Authorization: Bearer <accessToken>",
        "serializationRules": {
            "Long/long": "字符串（含 total/pageNum/pageSize/timestamp/所有 ID）",
            "Integer/int": "number",
            "Double": "number",
            "BigDecimal": "2 位小数字符串（所有金额）",
            "LocalDateTime": "yyyy-MM-dd HH:mm:ss（无时区后缀）",
            "LocalDate": "yyyy-MM-dd",
            "null 字段": "键缺失（non_null 策略），不是 null",
        },
        "responseEnvelope": {
            "code": "number，0=成功",
            "message": "string，可直接展示",
            "data": "业务数据；失败时该键缺失",
            "traceId": "string",
            "timestamp": "string（Long 序列化）",
        },
        "enums": {
            "orderStatus": {
                "10": "WAITING_ACCEPT 待接单",
                "20": "ACCEPTED 已接单",
                "30": "IN_SERVICE 服务中",
                "40": "PENDING_CONFIRM 待确认",
                "50": "COMPLETED 已完成",
                "60": "CANCELLED 已取消",
                "70": "ABNORMAL 异常处理中",
                "_note": "VO 里下发枚举名字符串；时间轴/finish/abnormal 里下发数字",
            },
            "serviceType": {
                "ACCOMPANY": "全程陪诊",
                "HALF_DAY": "半日陪护",
                "FULL_DAY": "全日陪护",
                "PICKUP_REPORT": "代取报告",
                "CONSULT_AGENT": "代问诊",
            },
            "roleMask": {"1": "USER", "2": "WORKER", "4": "ADMIN", "_note": "位掩码可叠加，陪诊师=3"},
        },
        "endpoints": endpoints,
    }

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"生成: {OUT}")
    print(f"接口总数: {len(endpoints)}（已实现 {out['implementedEndpoints']}）")


if __name__ == "__main__":
    main()
