import java.util.Scanner;
public class Main {

    public static void main(String[] args) {

        Allocation.startAutoReleaseScheduler();

        Scanner sc = new Scanner(System.in);
        int choice = 0;

        do {
            System.out.println("\n========== CRISISFLOW ==========");
            System.out.println("1. Register Emergency");
            System.out.println("2. Allocate Pending Resources Automatically");
            System.out.println("3. View All Emergencies");
            System.out.println("4. Register New Resource");
            System.out.println("5. View All Resources");
            System.out.println("6. Update Resource Status");
            System.out.println("7. Update Emergency Response Status");
            System.out.println("8. Exit");
            System.out.print("Enter choice: ");

            while (!sc.hasNextInt()) {
                System.out.print("Enter a valid number: ");
                sc.next();
            }

            choice = sc.nextInt();
            sc.nextLine();

            switch (choice) {

                // Register an emergency and optionally allocate resources
                case 1: {
                    System.out.print(
                            "Enter disaster type "
                            + "(Flood, Earthquake, Fire, Accident, Landslide): "
                    );
                    String type = sc.nextLine().trim();

                    System.out.print("Enter location: ");
                    String location = sc.nextLine().trim();

                    System.out.print("Enter number of people affected: ");

                    while (!sc.hasNextInt()) {
                        System.out.print("Enter a valid number: ");
                        sc.next();
                    }

                    int peopleAffected = sc.nextInt();
                    sc.nextLine();

                    if (peopleAffected <= 0
                            || type.isEmpty()
                            || location.isEmpty()) {
                        System.out.println(
                                "Invalid input. Please try again."
                        );
                        break;
                    }

                    System.out.print(
                            "Enter description (or press Enter to skip): "
                    );
                    String description = sc.nextLine().trim();

                    Emergency.register(
                            type,
                            location,
                            peopleAffected,
                            description,
                            sc
                    );

                    break;
                }

                // Automatically retry pending emergency allocations
                case 2: {
                    Allocation.allocatePendingAutomatically();
                    break;
                }

                // View all emergencies
                case 3: {
                    Emergency.viewEmergencies();
                    break;
                }

                // Register a new resource
                case 4: {
                    System.out.print("Enter resource name: ");
                    String name = sc.nextLine().trim();

                    if (name.isEmpty()) {
                        System.out.println(
                                "Resource name cannot be empty."
                        );
                        break;
                    }

                    System.out.println("\nResource Types:");
                    System.out.println("1. Ambulance");
                    System.out.println("2. Rescue Team");
                    System.out.println("3. Medical Team");
                    System.out.println("4. Fire Unit");
                    System.out.print("Enter resource type ID: ");

                    while (!sc.hasNextInt()) {
                        System.out.print("Enter a valid type ID: ");
                        sc.next();
                    }

                    int typeId = sc.nextInt();
                    sc.nextLine();

                    if (typeId < 1 || typeId > 4) {
                        System.out.println(
                                "Invalid resource type ID."
                        );
                        break;
                    }

                    System.out.print("Enter resource capacity: ");

                    while (!sc.hasNextInt()) {
                        System.out.print("Enter a valid capacity: ");
                        sc.next();
                    }

                    int capacity = sc.nextInt();
                    sc.nextLine();

                    if (capacity <= 0) {
                        System.out.println(
                                "Capacity must be greater than zero."
                        );
                        break;
                    }

                    System.out.print("Enter resource location: ");
                    String resourceLocation = sc.nextLine().trim();

                    if (resourceLocation.isEmpty()) {
                        System.out.println(
                                "Resource location cannot be empty."
                        );
                        break;
                    }

                    Resource.registerResource(
                            name,
                            typeId,
                            capacity,
                            resourceLocation
                    );

                    break;
                }

                // View all resources
                case 5: {
                    Resource.viewResources();
                    break;
                }

                // Update resource status
                case 6: {
                    Resource.viewResources();

                    System.out.print("\nEnter Resource ID to update: ");

                    while (!sc.hasNextInt()) {
                        System.out.print("Enter a valid resource ID: ");
                        sc.next();
                    }

                    int resourceId = sc.nextInt();
                    sc.nextLine();

                    System.out.println("\nAvailable status options:");
                    System.out.println("1. Available");
                    System.out.println("2. Out of Service");
                    System.out.print("Choose new status: ");

                    while (!sc.hasNextInt()) {
                        System.out.print("Enter 1 or 2: ");
                        sc.next();
                    }

                    int statusChoice = sc.nextInt();
                    sc.nextLine();

                    String newStatus;

                    if (statusChoice == 1) {
                        newStatus = "Available";
                    } else if (statusChoice == 2) {
                        newStatus = "Out of Service";
                    } else {
                        System.out.println(
                                "Invalid status choice."
                        );
                        break;
                    }

                    Resource.updateResourceStatus(
                            resourceId,
                            newStatus
                    );

                    break;
                }

                // Update emergency response status
                case 7: {
                    Emergency.viewEmergencies();

                    System.out.print(
                            "\nEnter Emergency ID to update: "
                    );

                    while (!sc.hasNextInt()) {
                        System.out.print("Enter a valid emergency ID: ");
                        sc.next();
                    }

                    int emergencyId = sc.nextInt();
                    sc.nextLine();

                    System.out.println("\nResponse Status:");
                    System.out.println("1. Reported");
                    System.out.println("2. Responding");
                    System.out.println("3. Resolved");
                    System.out.println("4. Cancelled");
                    System.out.print("Choose new status: ");

                    while (!sc.hasNextInt()) {
                        System.out.print("Enter a valid option: ");
                        sc.next();
                    }

                    int statusChoice = sc.nextInt();
                    sc.nextLine();

                    
                    String newStatus = "";

                    switch (statusChoice) {
                        case 1:
                            newStatus = "Reported";
                            break;

                        case 2:
                            newStatus = "Responding";
                            break;

                        case 3:
                            newStatus = "Resolved";
                            break;

                        case 4:
                            newStatus = "Cancelled";
                            break;

                        default:
                            System.out.println("Invalid status choice.");
                            break;
                    }

                    if (statusChoice < 1 || statusChoice > 4) {
                        break;
                    }


                    System.out.print(
                            "Enter update notes (or press Enter to skip): "
                    );
                    String notes = sc.nextLine().trim();

                    Emergency.updateResponseStatus(
                            emergencyId,
                            newStatus,
                            notes
                    );

                    break;
                }

                // Exit
                case 8: {
                    System.out.println(
                            "Exiting CrisisFlow. Stay safe!"
                    );
                    break;
                }

                default: {
                    System.out.println(
                            "Invalid choice. Please select 1 to 8."
                    );
                }
            }

        } while (choice != 8);

        sc.close();
    }
}
