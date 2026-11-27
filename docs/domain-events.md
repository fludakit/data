# Domain Events

!!! warning "Not implemented yet"
    Domain events are on the roadmap but there is **no API for them today**. Lifecycle operations do not raise events, and nothing is dispatched to the CDI container.

The intended design is to let an aggregate record domain events as its state changes (for example `OrderPlaced` when a new aggregate is saved) and have the generated repository publish them during the lifecycle operation, so application code can react through CDI observers instead of polling for changes. This is not part of the current release; watch the [issue tracker](https://github.com/fludakit/data/issues) for progress.
