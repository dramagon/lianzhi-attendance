package cn.lianzhi.attendance.knowledge.provider;

/** SUCCESS carries a value. UNAVAILABLE/FAILURE carry no partial value. */
public record ProviderResult<T>(State state, String message, T value) {
    public ProviderResult {
        java.util.Objects.requireNonNull(state); java.util.Objects.requireNonNull(message);
        if (message.isBlank()) throw new IllegalArgumentException("提供方结果说明不能为空");
        if (state == State.SUCCESS) java.util.Objects.requireNonNull(value, "成功结果必须包含输出");
        else if (value != null) throw new IllegalArgumentException("不可用或失败结果不得包含输出");
    }
    public enum State { SUCCESS, UNAVAILABLE, FAILURE }
}
