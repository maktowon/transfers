# Transfers
This is translated description of the task from Concurrent Programming course with my shortened description of what the main goal is about.

*Long story short:* The task involves designing a system for managing components stored on devices. These components can be added, moved, or removed within the system through transfers. Sometimes transfer may be possible but it has to wait for the slot on the source device to be freed. We need to handle such cases and let them to be transfered as soon as the slot is freed. There is also a possibilty to add a completely new component on device as well as removing component from a device and making it to leave the whole system. Obviously the task need to follow strict rules of security, liveness and handle error cases. The more detailed description is written below.

# Specification

In our system model, data is grouped into components and stored on devices. Both each device and each data component are assigned an immutable and unique identifier within the system (objects of the classes `cp2023.base.DeviceId` and `cp2023.base.ComponentId`, respectively). Each device also has a specified capacity, which is the maximum number of components it can store at any given time. The assignment of components to devices is managed by the system (an object implementing the `cp2023.base.StorageSystem` interface, as presented below):

```java
public interface StorageSystem {

  void execute(ComponentTransfer transfer) throws TransferException;

}
```


Specifically, each component existing in the system is located on exactly one device, unless the user initiates the transfer of that component to another device (by calling the `execute` method of the `StorageSystem` class and passing an object implementing the `cp2023.base.ComponentTransfer` interface as a parameter, representing the requested transfer).

```java
public interface ComponentTransfer {

    public ComponentId getComponentId();
    
    public DeviceId getSourceDeviceId();
    
    public DeviceId getDestinationDeviceId();
    
    public void prepare();
    
    public void perform();

}
```
A component transfer is also initiated when the user wants to add a new component to the system (in this case, the `getSourceDeviceId` method of the transfer object returns `null`) or remove an existing component from the system (in this case, symmetrically, the `getDestinationDeviceId` method of the transfer object returns `null`). In other words, a single transfer represents one of the three available operations on a component:

1. Adding a new component to a device in the system (`getSourceDeviceId` returns `null`, and `getDestinationDeviceId` returns a non-null value indicating the identifier of the device where the added component should be located).
2. Moving an existing component between devices in the system (`getSourceDeviceId` and `getDestinationDeviceId` both return non-null values representing the identifiers of the current device where the component is located and the destination device where the component should be after the transfer, respectively).
3. Removing an existing component from a device and, consequently, the system (`getSourceDeviceId` returns a non-null value indicating the identifier of the device where the component is located, and `getDestinationDeviceId` returns `null`).

The initiation of the three mentioned types of operations by the user is beyond the control of the implemented solution. Your solution's task is to carry out the requested transfers synchronously (i.e., if the requested transfer is valid, the `execute` method called on the object implementing `StorageSystem` with the transfer represented as a parameter cannot complete its operation until the transfer is complete). As many different operations may be initiated simultaneously by the user, your implemented system must coordinate them according to the following rules.

At any given moment, for a given component, at most one transfer can be requested. Until this transfer is complete, the component is considered transferred, and any subsequent transfer requested for this component should be treated as invalid.

The component transfer itself is a two-step process and may take a longer time, especially its second step. Initiating a transfer involves its preparation (i.e., calling the `prepare` method on an object with the `ComponentTransfer` interface representing the transfer). Only after such preparation can the data constituting the component be transmitted (accomplished by calling the `perform` method on the same object). When the data is transmitted (i.e., the `perform` method completes its operation), the transfer is considered complete. Both mentioned methods must be executed in the context of the thread initiating the transfer.

# Security

Transfers can be valid or invalid. The security requirements below apply to valid transfers. Handling invalid transfers is described in the subsequent section.

If a transfer represents a component removal operation, its initiation is allowed without any additional prerequisites. Otherwise, the initiation of a transfer is allowed if there is space on the destination device for the transferred component. Specifically, this space is either currently available or will be freed up, allowing it to be reserved. More precisely, the initiation of a transfer representing the movement or addition of component Cx is allowed if one of the following conditions is met:

1. On the destination device, there is free space for a component that has not been reserved by the system for another component that is/will be moved/added to this device.
2. On the destination device, there is a component Cy transferred from this device, whose transfer has started or its initiation is allowed, and the space freed up by this component has not been reserved by the system for another component.
3. Component Cx belongs to a set of transferred components, where the destination device for each component in the set is the device containing exactly one other component from the set, and the space for none of the components in the set has been reserved for a component outside the set.

If the transfer of component Cx is allowed but is to take place in a location still occupied by another transferred component Cy (the last two cases above), then the second stage of the transfer of component Cx (i.e., calling the `perform` function for this transfer) cannot begin until the first stage of the transfer of component Cy (i.e., calling the `prepare` function for this transfer) is complete.

Of course, if the transfer of a component is not allowed, it cannot be initiated (i.e., neither the `prepare` nor the `perform` function can be called on the object representing this transfer).

Your solution should absolutely ensure all the above security conditions.

# Liveliness

As for liveliness, the transfer (both its `prepare` and `perform` phases) should start as soon as it is allowed, and the remaining security requirements are met. In the case where multiple transfers compete for space on a device, among those that are allowed, your algorithm should locally prioritize transfers waiting longer for this device. Globally, this can potentially lead to the starvation of certain transfers (we encourage you to come up with a scenario for such a situation). Solving this problem is possible but complicates the code beyond what we would like to require from you. Therefore, it should not be implemented, especially since, in practice, a system user seeing that a transfer to a certain device is taking a long time could transfer other components from that device.

# Error Handling

Finally, the proposed solution should check whether the transfer requested by the user is invalid (resulting in the `execute` method of the `StorageSystem` interface raising the appropriate exception inheriting from the `cp2023.exceptions.TransferException` class). According to previous explanations, a transfer is invalid if at least one of the following conditions is met:

1. The transfer does not represent any of the three available operations on components or does not indicate any component (exception: `IllegalTransferType`).
2. The device indicated by the transfer as the source or destination does not exist in the system (exception: `DeviceDoesNotExist`).
3. A component with an identifier equal to the one being added within the transfer already exists in the system (exception: `ComponentAlreadyExists`).
4. A component with an identifier equal to the one being removed or moved within the transfer does not exist in the system or is located on a different device than indicated by the transfer (exception: `ComponentDoesNotExist`).
5. The component related to the transfer is already on the device indicated by the transfer as the destination (exception: `ComponentDoesNotNeedTransfer`).
6. The component related to the transfer is still being transferred (exception: `ComponentIsBeingOperatedOn`).

The solution can adopt any sensible order for checking these conditions.
