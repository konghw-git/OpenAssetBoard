package com.assetboard.data

/**
 * Five groups and fifteen accounts with plausible balances, for screenshots and tests. Test-only:
 * a fresh install starts empty.
 */
suspend fun LedgerRepository.seedDemo(at: Long = System.currentTimeMillis()) {
    data class Seed(val name: String, val purpose: String?, val cents: Long, val kind: AccountKind = AccountKind.NORMAL, val institution: String? = null)
    val plan = listOf(
        Triple("现金账户", false, listOf(Seed("现金", null, 0))),
        Triple(
            "储蓄账户", false,
            listOf(
                Seed("农行①", "日常消费", 31, institution = "中国农业银行"),
                Seed("农行②", "暂时不用", 1, institution = "中国农业银行"),
                Seed("邮储", "周转资金", 100, institution = "中国邮政储蓄银行"),
                Seed("SJTU", "理财", 166024),
                Seed("宁波银行", null, 0, institution = "宁波银行"),
            ),
        ),
        Triple(
            "虚拟账户", false,
            listOf(
                Seed("微信钱包", "日常消费", 273481, institution = "微信"),
                Seed("余额宝", null, 296200, institution = "支付宝"),
            ),
        ),
        Triple(
            "负债账户", true,
            listOf(
                Seed("校园贷款", null, 2000000),
                Seed("花呗", null, 0, institution = "支付宝"),
                Seed("抖音月付", null, 0),
                Seed("美团月付", null, 0),
                Seed("白条", null, 0, institution = "京东"),
            ),
        ),
        Triple(
            "投资账户", false,
            listOf(
                Seed("招商产业债券A", "3年后", 1664911, AccountKind.INVESTMENT),
                Seed("兴证全球恒悦180天持有期债券A", "半年强制存储", 1120940, AccountKind.INVESTMENT),
                Seed("进阶理财", null, 326525, AccountKind.INVESTMENT),
            ),
        ),
    )
    plan.forEachIndexed { gi, (groupName, liability, seeds) ->
        val gid = createGroup(groupName, liability, gi)
        for (s in seeds) createAccount(AccountDraft(gid, s.name, s.purpose, s.kind, s.institution), at, s.cents, null)
    }
}
