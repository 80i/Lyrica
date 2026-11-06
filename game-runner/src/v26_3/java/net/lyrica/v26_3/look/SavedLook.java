package net.lyrica.v26_3.look;

/** A look the player named. Only its look fields are ever taken back, see {@link Look#copyLookFrom}. */
public final class SavedLook {

  public String name;
  public Look look;

  public SavedLook() {
  }

  public SavedLook(String name, Look look) {
    this.name = name;
    this.look = new Look();
    this.look.copyLookFrom(look);
    this.look.saved = null;
  }

  public void apply(Look target) {
    target.copyLookFrom(this.look);
  }
}
