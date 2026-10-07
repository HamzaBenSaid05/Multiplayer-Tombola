
package Project_Tombola.game;

/** * Enum representing the prizes available in a Tombola game. * * <p>Each enum constant associates: * <ul> *   <li>a display name (localized string used in the UI), and</li> *   <li>the number of marks required on a player's card to claim that prize.</li> * </ul> * * <p>The prize progression is: * NONE -> AMBO -> TERNA -> QUATERNA -> CINQUINA -> TOMBOLA * * <p>This type is immutable and safe to use from multiple threads; it only contains * final fields and pure accessor methods. */
public enum Prize {
    /** No prize / none. Display name: "Nessuno". Required marks: 0. */
    NONE("Nessuno", 0),

    /** Ambo (two marks). Display name: "Ambo". Required marks: 2. */
    AMBO("Ambo", 2),

    /** Terna (three marks). Display name: "Terna". Required marks: 3. */
    TERNA("Terna", 3),

    /** Quaterna (four marks). Display name: "Quaterna". Required marks: 4. */
    QUATERNA("Quaterna", 4),

    /** Cinquina (five marks). Display name: "Cinquina". Required marks: 5. */
    CINQUINA("Cinquina", 5),

    /** Tombola (full house). Display name: "Tombola". Required marks: 15. */
    TOMBOLA("Tombola", 15);

    /**     * The localized, human-readable name used in the UI for this prize.     * Examples: "Ambo", "Terna", "Tombola".     */
    private final String displayName;

    /**     * The number of marked numbers required to claim this prize.     * For example, AMBO requires 2, TERNA requires 3, TOMBOLA requires 15.     */
    private final int required;

    /**     * Construct a Prize enum constant with its display name and requirement.     *     * @param displayName localized display name used in UI     * @param required number of marks required to claim the prize     */
    Prize(String displayName, int required) {
        this.displayName = displayName;
        this.required = required;
    }

    /**     * Return the localized display name for this prize.     *     * @return display name (not null)     */
    public String getDisplayName() { return displayName; }

    /**     * Return how many marked numbers are required to claim this prize.     *     * @return required number of marks (non-negative)     */
    public int getRequired() { return required; }

    /**     * Return the next prize in the normal progression.     *     * <p>Progression:     * NONE -> AMBO -> TERNA -> QUATERNA -> CINQUINA -> TOMBOLA     *     * <p>Calling next() on CINQUINA or TOMBOLA returns TOMBOLA (the final state).     *     * @return the next Prize in the progression (never null)     */
    public Prize next() {
        return switch (this) {
            case NONE -> AMBO;
            case AMBO -> TERNA;
            case TERNA -> QUATERNA;
            case QUATERNA -> CINQUINA;
            case CINQUINA, TOMBOLA -> TOMBOLA;
        };
    }
}