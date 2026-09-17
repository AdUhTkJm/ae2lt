这篇文章也是人类写成的。

# Thunderbolt 

如 README 所说，Thunderbolt 是一个独立的模组，不安装闪电科技也有效。

它主要提供了一些 mixin，也就是对 AE2 本体函数的修改。

## CraftingCalculation

在[上一篇文档](01%20-%20AE2%20的内部实现.md#合成计划)中，我们提到了 `CraftingCalculation` 负责规划合成，并主要是通过调用 `CraftingTreeNode#request` 进行 DFS 实现的。而 Thunderbolt 对这里进行了 mixin。

```java
public ICraftingPlan run() {
    try {
        TickHandler.instance().registerCraftingSimulation(this.level, this);
        this.handlePausing();

        var plan = computePlan(); // <--- 在这里
        this.logCraftingJob(plan);
        return plan;
    } catch (Exception ex) {
        AELog.info(ex, "Exception during crafting calculation.");
        throw new RuntimeException(ex);
    } finally {
        this.finish();
    }
}
```


