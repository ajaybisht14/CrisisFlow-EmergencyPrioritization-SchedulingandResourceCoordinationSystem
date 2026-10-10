
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class Database {

    private static final String URL =
            "jdbc:mysql://localhost:3306/crisisflow_v2"
            + "?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=UTC";

    private static final String USER =
            System.getenv("CRISISFLOW_DB_USER");

    private static final String PASSWORD =
            System.getenv("CRISISFLOW_DB_PASSWORD");

    public static Connection getConnection() throws SQLException {

        if (USER == null || PASSWORD == null) {
            throw new SQLException(
                    "Database credentials are missing. Set the environment variables first."
            );
        }

        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}