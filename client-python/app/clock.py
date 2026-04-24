class LogicalClock:
    def __init__(self) -> None:
        self.value = 0

    def tick(self) -> int:
        self.value += 1
        return self.value

    def update(self, received_value: int) -> int:
        self.value = max(self.value, received_value)
        return self.value

    def get_value(self) -> int:
        return self.value