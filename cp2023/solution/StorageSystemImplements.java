/*
 * University of Warsaw
 * Concurrent Programming Course 2023/2024
 * Java Assignment
 *
 * Author: Maciej Nowotka
 */
package cp2023.solution;

import cp2023.base.ComponentId;
import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.base.StorageSystem;
import cp2023.exceptions.*;

import java.util.*;
import java.util.concurrent.Semaphore;

public class StorageSystemImplements implements StorageSystem {
    private final HashSet<DeviceId> devices_set;
    private final HashSet<ComponentId> components_set;
    private final HashSet<ComponentId> operated_components = new HashSet<>();

    private final HashMap<ComponentId, DeviceId> component_to_device;

    private final HashMap<ComponentId, Semaphore> component_to_prepare_mutex = new HashMap<>();
    private final HashMap<ComponentId, Semaphore> component_to_perform_mutex = new HashMap<>();

    // map device to "queue" of components waiting (components which can perform cycle -> their dev source is not null)
    private final HashMap<DeviceId, List<ComponentId>> device_to_queue_cycle = new HashMap<>();
    // map device to "queue" of all components waiting, including the new components
    private final HashMap<DeviceId, List<ComponentId>> device_to_queue_general = new HashMap<>();

    // map saying that key component should awake value component when key component finishes its prepare phase
    private final HashMap<ComponentId, ComponentId> component_to_component_perform = new HashMap<>();

    private final HashMap<DeviceId, Integer> device_to_free_slots;
    // map device to slots which are freeing
    private final HashMap<DeviceId, Integer> device_to_shifting_slots = new HashMap<>();
    // map device to List of components which are leaving the device
    private final HashMap<DeviceId, List<ComponentId>> device_to_components_freeing_slots = new HashMap<>();

    // mutxe for accesing all variables
    private final Semaphore MUTEX = new Semaphore(1, true);

    // stack of components which I will be letting to prepare
    private final Stack<ComponentId> graph = new Stack<>();
    private boolean cycle = false, can_i_prepare = false, can_i_perform = false;

    public StorageSystemImplements(Map<DeviceId, Integer> deviceTotalSlots,
                                   Map<ComponentId, DeviceId> componentPlacement) {
        if (deviceTotalSlots == null)
            throw new IllegalArgumentException("deviceTotalSlots is null");
        if (deviceTotalSlots.size() == 0)
            throw new IllegalArgumentException("deviceTotalSlots is an empty map");
        devices_set = new HashSet<>(deviceTotalSlots.keySet());
        if (devices_set.contains(null))
            throw new IllegalArgumentException("deviceTotalSlots has null value as a key.");

        if (componentPlacement == null)
            components_set = new HashSet<>();
        else
            components_set = new HashSet<>(componentPlacement.keySet());
        if (components_set.contains(null))
            throw new IllegalArgumentException("componentPlacement has null value as a key.");

        if (componentPlacement == null)
            component_to_device = new HashMap<>();
        else
            component_to_device = new HashMap<>(componentPlacement);
        if (component_to_device.containsValue(null))
            throw new IllegalArgumentException("Some component is placed on the null device.");

        device_to_free_slots = new HashMap<>(deviceTotalSlots);
        if (device_to_free_slots.containsValue(null))
            throw new IllegalArgumentException("Some device has null value of slots.");
        if (device_to_free_slots.containsValue(0))
            throw new IllegalArgumentException("Some device has 0 slots.");

        for (Map.Entry<ComponentId, DeviceId> pair : component_to_device.entrySet()) {
            ComponentId component = pair.getKey();
            DeviceId device = pair.getValue();
            if (!devices_set.contains(device))
                throw new IllegalArgumentException("Devcie" + device.toString() + " is not a key of deviceTotalSlots map.");
            Integer slots_available = device_to_free_slots.get(device);
            if (slots_available == 0)
                throw new IllegalArgumentException("Devcie" + device.toString() + " has run out of slots.");
            if (slots_available < 0)
                throw new IllegalArgumentException("Devcie" + device.toString() + " has negative value of slots set.");
            device_to_free_slots.put(device, slots_available - 1);
            component_to_prepare_mutex.put(component, new Semaphore(0));
            component_to_perform_mutex.put(component, new Semaphore(0));
        }

        for (DeviceId device : devices_set) {
            device_to_queue_cycle.put(device, new ArrayList<>());
            device_to_queue_general.put(device, new ArrayList<>());
            device_to_components_freeing_slots.put(device, new ArrayList<>());
            device_to_shifting_slots.put(device, 0);
        }
    }

    private void check_transfer(DeviceId device_source, DeviceId device_destination, ComponentId component)
            throws TransferException {
        if (device_source == null && device_destination == null)
            throw new IllegalTransferType(component);
        if (device_source == null) {
            if (components_set.contains(component) && component_to_device.get(component) == null) {
                throw new ComponentAlreadyExists(component);
            }
        }
        if (operated_components.contains(component))
            throw new ComponentIsBeingOperatedOn(component);
        if (device_source == null) {
            if (components_set.contains(component) && component_to_device.get(component) != null) {
                throw new ComponentAlreadyExists(component, component_to_device.get(component));
            }
        }
        if (device_source != null) {
            if (!devices_set.contains(device_source))
                throw new DeviceDoesNotExist(device_source);
            if (!components_set.contains(component) || !component_to_device.get(component).equals(device_source))
                throw new ComponentDoesNotExist(component, device_source);
        }
        if (device_destination != null) {
            if (!devices_set.contains(device_destination))
                throw new DeviceDoesNotExist(device_destination);
            if (device_source != null && component_to_device.get(component).equals(device_destination))
                throw new ComponentDoesNotNeedTransfer(component, device_destination);
        }
    }

    private void find_cycle(ComponentId component_actual, DeviceId device_actual, DeviceId end_of_cycle) {
        graph.push(component_actual);
        if (device_actual.equals(end_of_cycle)) {
            cycle = true;
            return;
        }
        ComponentId component_to_remove = null;
        List<ComponentId> queue_cycle = device_to_queue_cycle.get(device_actual);
        for (ComponentId next_component : queue_cycle) {
            find_cycle(next_component, component_to_device.get(next_component), end_of_cycle);
            if (cycle) {
                component_to_remove = next_component;
                break;
            }
        }
        if (cycle) {
            List<ComponentId> queue_general = device_to_queue_general.get(device_actual);
            queue_general.remove(component_to_remove);
            queue_cycle.remove(component_to_remove);
            return;
        }
        graph.pop();
    }

    private void find_path(ComponentId component_actual, DeviceId device_actual) {
        graph.push(component_actual);
        if (device_actual == null)
            return;
        List<ComponentId> queue_general = device_to_queue_general.get(device_actual);
        if (queue_general.isEmpty())
            return;
        ComponentId next_component = queue_general.get(0);
        queue_general.remove(next_component);
        device_to_queue_cycle.get(device_actual).remove(next_component);
        find_path(next_component, component_to_device.get(next_component));
    }

    private void set_prepare_perform(ComponentId component, DeviceId device_destination) {
        // if component is leaving the system it can prepare as well as perform
        if (device_destination == null) {
            can_i_prepare = true;
            can_i_perform = true;
        }
        // the same goes if the component is going to take a free slot
        else if (device_to_free_slots.get(device_destination) > 0) {
            device_to_free_slots.put(device_destination, device_to_free_slots.get(device_destination) - 1);
            can_i_prepare = true;
            can_i_perform = true;
        }
        // however we are reserving the place which will be freeing, we cant perform directly so we need to inform
        // some component to wake us up to perform
        else if (device_to_shifting_slots.get(device_destination) > 0) {
            device_to_shifting_slots.put(device_destination, device_to_shifting_slots.get(device_destination) - 1);
            ComponentId component_waking_me_up = device_to_components_freeing_slots.get(device_destination).remove(0);
            component_to_component_perform.put(component_waking_me_up, component);
            can_i_prepare = true;
        }
    }

    private void solve_for_path(ComponentId component, DeviceId device_source) {
        // if the component can perform we will annotate that
        if (can_i_perform) {
            component_to_perform_mutex.get(component).release();
        }
        find_path(component, device_source);
        ComponentId last_component = graph.peek(), previous = null;
        DeviceId last_device = component_to_device.get(last_component);

        // if the component is on device, we increase its shifting slots by one and add the component to the components
        // which are freeing the slots
        if (last_device != null) {
            device_to_shifting_slots.put(last_device, device_to_shifting_slots.get(last_device) + 1);
            device_to_components_freeing_slots.get(last_device).add(last_component);
        }
        // we empty the path, and let the components on it prepare, but also annotate the fact which componets
        // to wake after the prepare phase
        while (!graph.empty()) {
            ComponentId next_component = graph.pop();
            component_to_prepare_mutex.get(next_component).release();
            component_to_component_perform.put(next_component, previous);
            previous = next_component;
        }
    }

    private void solve_for_cycle(ComponentId component, DeviceId device_source, DeviceId device_destination) {
        find_cycle(component, device_source, device_destination);
        // we do the same for cycle as for the path, but we initialize the previous component to the actual one
        if (cycle) {
            ComponentId previous = component;
            while (!graph.empty()) {
                ComponentId next_component = graph.pop();
                component_to_prepare_mutex.get(next_component).release();
                component_to_component_perform.put(next_component, previous);
                previous = next_component;
            }
        }
        // if the cycle is not occurring in the graph we simply add the component to the queues
        else {
            device_to_queue_cycle.get(device_destination).add(component);
            device_to_queue_general.get(device_destination).add(component);
        }
        cycle = false;
    }

    private void clean_after_prepare(ComponentId component, DeviceId device_source) {
        // if we are new we have nothing to do
        if (device_source == null)
            return;

        ComponentId component_waiting_for_perform = component_to_component_perform.get(component);

        // if the component is transfering from another device, we need to wake up some component or...
        if (component_waiting_for_perform != null) {
            component_to_perform_mutex.get(component_waiting_for_perform).release();
            component_to_component_perform.put(component, null);
        }
        // if such a component does not exist we change the shifting slot to free slot since the component is leaving
        // its device
        else {
            device_to_shifting_slots.put(device_source, device_to_shifting_slots.get(device_source) - 1);
            device_to_free_slots.put(device_source, device_to_free_slots.get(device_source) + 1);
            device_to_components_freeing_slots.get(device_source).remove(component);
        }
    }

    private void clean_after_perform(ComponentId component, DeviceId device_destination) {
        // after the perform phase we only have to annotate that the component is no more being operated on
        // and change our device or if the component is completely leaving the system we should annotate that as well
        operated_components.remove(component);
        component_to_device.put(component, device_destination);
        if (device_destination == null)
            components_set.remove(component);
    }

    @Override
    public void execute(ComponentTransfer transfer) throws TransferException {
        ComponentId component = transfer.getComponentId();
        DeviceId device_source = transfer.getSourceDeviceId(), device_destination = transfer.getDestinationDeviceId();
        try {
            MUTEX.acquire();
            check_transfer(device_source, device_destination, component);
            if (device_source == null) {
                components_set.add(component);
                component_to_prepare_mutex.put(component, new Semaphore(0));
                component_to_perform_mutex.put(component, new Semaphore(0));
            }
            operated_components.add(component);

            // those variables are only needed in the first phase, so we can override them without worries
            can_i_prepare = false;
            can_i_perform = false;
            cycle = false;

            set_prepare_perform(component, device_destination);
            if (can_i_prepare) {
                // if the component can start preparing it is not creating a cycle, so we are creating a path
                solve_for_path(component, device_source);
            }
            else if (device_source == null) {
                // the component is new and there is no space for it on its destination device
                device_to_queue_general.get(device_destination).add(component);
            }
            else {
                // if the component is not a new one, and it cant start prepare phase its last chance is to create a cycle
                solve_for_cycle(component, device_source, device_destination);
            }
            MUTEX.release();

            component_to_prepare_mutex.get(component).acquire();
            transfer.prepare();

            MUTEX.acquire();
            clean_after_prepare(component, device_source);
            MUTEX.release();

            component_to_perform_mutex.get(component).acquire();
            transfer.perform();

            MUTEX.acquire();
            clean_after_perform(component, device_destination);
            MUTEX.release();

        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }
}
