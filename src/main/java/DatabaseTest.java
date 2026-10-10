
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class DatabaseTest {

    public static void main(String[] args) {

        String url = "jdbc:mysql://localhost:3306/crisisflow_v2"
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC";

        String user = System.getenv("CRISISFLOW_DB_USER");
        String password = System.getenv("CRISISFLOW_DB_PASSWORD");

        if (user == null || password == null) {
            System.out.println("Database credentials are missing.");
            System.out.println("Set CRISISFLOW_DB_USER and CRISISFLOW_DB_PASSWORD.");
            return;
        }

        try (
            Connection con = DriverManager.getConnection(url, user, password);
            Statement stmt = con.createStatement();
            ResultSet rs = stmt.executeQuery(
                "SELECT DATABASE(), COUNT(*) "
                + "FROM information_schema.tables "
                + "WHERE table_schema = DATABASE()"
            )
        ) {
            if (rs.next()) {
                System.out.println("MySQL connected successfully!");
                System.out.println("Database: " + rs.getString(1));
                System.out.println("Tables found: " + rs.getInt(2));
            }

        } catch (Exception e) {
            System.out.println("Database connection failed.");
            System.out.println("Error: " + e.getMessage());
        }
    }
}