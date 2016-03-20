package models;

import managers.AccountManager;
import play.db.jpa.Transactional;

public class Login {

    public String email;
    public String password;
    public String rememberMe;

    @Transactional
    public String validate() {
        if (AccountManager.authenticate(this.email, this.password) == null) {
            return "Ungültige Zugangsdaten!";
        }
        return null;
    }
}