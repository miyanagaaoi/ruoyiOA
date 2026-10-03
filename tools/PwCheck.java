import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PwCheck {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = "$2a$10$lpCCpcruhXjSgz5rfN0P3OQOvOqJxSVOazBW48PQejBdNzs9x.Smq";
        String[] candidates = {
            "admin123", "admin", "123456", "superAdmin", "superadmin", "test123", "test",
            "ruoyi123", "ruoyi", "admin@123", "Admin123", "admin888", "12345678", "123456789",
            "oa123456", "superAdmin123", "Admin@123", "admin123456", "password", "123123",
            "qwer1234", "abc123", "admin@2025", "RuoYi123", "ruoyi@123"
        };
        boolean found = false;
        for (String p : candidates) {
            if (encoder.matches(p, hash)) {
                System.out.println("MATCH: " + p);
                found = true;
            }
        }
        if (!found) {
            System.out.println("NO MATCH in candidate list");
        }
    }
}
