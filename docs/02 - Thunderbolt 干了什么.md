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

它想要重写 `computePlan` 方法，让它可以自由选择规划算法。为此，我们需要一个简单的、装有规划算法的 record：

```java
public record PlanningChoice(Kind kind, @Nullable ResourceLocation engineId) {}
```

这里的 `kind` 来自内部的 enum，只能是 `VANILLA` （表示 AE2 原生算法）或者 `ENGINE`（表示这个 mod 自己的算法）。后面的 `engineId` 只在 `kind == ENGINE` 时非空，指定究竟用哪个。

那么，我们该如何知道有哪些引擎呢？我们肯定想在 `computePlan` 调用之前就得到所有的可选算法，那我们自然可以 mixin `CraftingService#beginCraftingCalculation`。

```java
public Future<ICraftingPlan> beginCraftingCalculation(...) {
    if (level == null || simRequester == null) {
        throw new IllegalArgumentException("Invalid Crafting Job Request");
    }

    final CraftingCalculation job = new CraftingCalculation(level, grid, simRequester,
            new GenericStack(what, amount), strategy);

    // <--- 在这里插入：thunderbolt$configurePlanning
    return CRAFTING_POOL.submit(job::run);
}
```

为了知道可用的算法，我们需要考虑网络中的机器。而要进行和 AE 网络相关的操作，就可以使用 AE2 提供的接口：`IGridService`。这个接口是空的，你可以继承它并实现你想要的功能，只需要通过 `IGrid#getService` 就可以找到它了。

而要实现一个 service，我们就需要注册一个 service provider。它可以重写 `on{Server,Client}Tick{Start,End}()` 函数，以及改变网络拓扑结构的函数（`{add,remove}Node()`），从而得到它想要关注的信息。

在 Thunderbolt 中，我们有实现了 `IGridService` 的 `ICraftingPlanningService`，它只有一个 `List<PlanningChoice> resolve()` 函数。而它的具体实现则是在去掉 `I` 的那个类里：

```java
class CraftingPlanningService implements
    ICraftingPlanningService, // 实现 service 本身的函数
    IGridServiceProvider,     // 监听网络变化
```

具体实现就是在加入/移除网络节点时记录一下网络中能提供新算法的机器，再根据各种东西排个序，没什么特别的。

接下来，我们就可以看看这个修改后的 `computePlan` 具体是怎么工作的了。这里有很多仅仅为了 DEBUG 级别的日志而存在的内容，我们略过不看。

大概思路就是：如果选择的是 vanilla，那么自然就使用原本的 `computePlan`；如果选择的是其他算法，那么就顺次执行，第一个超时了就换下一个。

为了管理这些算法的时间，Thunderbolt 制作了一个 `PlanningAttemptMonitor`。太复杂了我没看，总之相信它可以管理好时间、中途取消以及报错就可以了。不论如何，在经过一大堆错误处理之后，如果一切顺利，这个东西最后依然会返回一个 `ICraftingPlan`。

那么这究竟是如何和引擎沟通的呢？大致结构和原本的 `computePlan` 相似，如果能直接合成就合成，如果不能而且允许合成更少就二分，否则就能合成多少就合成多少，并返回 Simulate。唯一的区别是把 `CraftingCalculation` 里用来规划的 `runCraftAttempt` 改成了算法引擎自己的方法。

既然都在写 Java 了，那是不得不抽象的。所以 Thunderbolt 抽象出了一个接口：
```java
public interface PlanningEngineSession extends AutoCloseable {
    // 尝试合成这么多物品。
    PlanningAttempt attempt(long amount, boolean simulate, PlanningAttemptContext context);

    // 最后的处理。
    default ICraftingPlan finish(ICraftingPlan result, PlanningAttemptContext context) {
        return result;
    }

    // AutoCloseable 的实现，用来释放资源。
    @Override
    default void close() {}
}
```

这里的 `PlanningAttemptContext` 也是个接口，实际上是个管理时间片的东西。实现它的就是刚提到的 `PlanningAttemptMonitor`。这个就跟 `CraftingTreeNode` 中每次都要调用 `handlePausing()` 效果是一样的：依靠线程自觉主动地将时间片让出去。

接下来，我们就可以看看 Thunderbolt 内部的算法了。

## Planning Engine V2

目前已经完整实现的算法引擎位于 `ThunderboltV2PlanningEngine`。它其实只是一个包装，为了满足算法引擎所需要的接口。算法本身在 `FastCraftingPlanner` 内实现。


